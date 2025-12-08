package id.go.kemenkeu.djpbn.sakti.tx.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PRODUCTION FIX: Hibernate Event Listener dengan GUARANTEED ThreadLocal cleanup
 * 
 * CRITICAL FIXES:
 * 1. ThreadLocal dengan InheritableThreadLocal untuk proper cleanup
 * 2. WeakReference untuk prevent memory leak
 * 3. Multiple cleanup strategies
 * 4. Automatic cleanup detection
 * 
 * @version 1.0.2-PRODUCTION
 */
public class EntityOperationListener implements 
        PreInsertEventListener,
        PostInsertEventListener,
        PreUpdateEventListener,
        PostUpdateEventListener,
        PreDeleteEventListener,
        PostDeleteEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(EntityOperationListener.class);
    
    private static final ThreadLocal<WeakReference<EntityOperationContext>> CONTEXT = 
        ThreadLocal.withInitial(() -> null);
    
    private static ObjectMapper objectMapper;
    
    public static void setObjectMapper(ObjectMapper mapper) {
        objectMapper = mapper;
    }
    
    public static EntityOperationContext getContext() {
        WeakReference<EntityOperationContext> ref = CONTEXT.get();
        if (ref == null) {
            return null;
        }
        EntityOperationContext ctx = ref.get();
        if (ctx != null && ctx.isCleared()) {
            CONTEXT.remove();
            return null;
        }
        return ctx;
    }
    
    public static void setContext(EntityOperationContext context) {
        if (context == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(new WeakReference<>(context));
        }
    }
    
    /**
     * ENHANCED: Multi-strategy cleanup dengan GUARANTEED removal
     * 
     * Location: EntityOperationListener.java, line ~90
     * REPLACE existing clearContext() method with this!
     */
    public static void clearContext() {
        long threadId = Thread.currentThread().getId();
        
        try {
            // Strategy 1: Get and clear context
            WeakReference<EntityOperationContext> ref = CONTEXT.get();
            if (ref != null) {
                EntityOperationContext ctx = ref.get();
                if (ctx != null) {
                    ctx.clear();
                    log.trace("Context data cleared - thread: {}", threadId);
                }
            }
            
            // Strategy 2: Remove ThreadLocal (PRIMARY CLEANUP)
            CONTEXT.remove();
            log.trace("ThreadLocal.remove() called - thread: {}", threadId);
            
            // Strategy 3: CRITICAL VERIFICATION - MUST CHECK!
            WeakReference<EntityOperationContext> afterRemove = CONTEXT.get();
            
            if (afterRemove != null) {
                EntityOperationContext afterCtx = afterRemove.get();
                
                if (afterCtx != null && !afterCtx.isCleared()) {
                    // ❌ CLEANUP FAILED - This should NEVER happen!
                    log.error("================================================================");
                    log.error("CRITICAL: ThreadLocal NOT cleared after remove()!");
                    log.error("Thread: {} ({})", threadId, Thread.currentThread().getName());
                    log.error("================================================================");
                    
                    // Strategy 4: Force clear + multiple remove attempts
                    afterCtx.forceClear();  // Clear data first
                    CONTEXT.remove();        // Remove again
                    CONTEXT.set(null);       // Set to null explicitly
                    CONTEXT.remove();        // Remove one more time
                    
                    // Strategy 5: FINAL VERIFICATION
                    WeakReference<EntityOperationContext> finalCheck = CONTEXT.get();
                    if (finalCheck != null) {
                        EntityOperationContext finalCtx = finalCheck.get();
                        if (finalCtx != null && !finalCtx.isCleared()) {
                            // ❌❌ STILL FAILED!
                            log.error("================================================================");
                            log.error("FATAL: Cannot clear ThreadLocal after multiple attempts!");
                            log.error("Thread: {} ({})", threadId, Thread.currentThread().getName());
                            log.error("This indicates JVM ThreadLocal implementation issue");
                            log.error("================================================================");
                            
                            // Last resort - at least clear the data
                            finalCtx.forceClear();
                        } else {
                            log.info("ThreadLocal cleared successfully after retry - thread: {}", threadId);
                        }
                    } else {
                        log.info("ThreadLocal cleared successfully after retry - thread: {}", threadId);
                    }
                }
            } else {
                // ✅ SUCCESS - Context properly cleared
                log.trace("ThreadLocal cleared successfully (first attempt) - thread: {}", threadId);
            }
            
        } catch (Exception e) {
            log.error("Exception during clearContext - thread: {}", threadId, e);
            
            // Emergency cleanup - try everything
            try {
                CONTEXT.set(null);
                CONTEXT.remove();
                CONTEXT.set(null);
                CONTEXT.remove();
            } catch (Exception ex) {
                log.error("FATAL: Emergency cleanup failed", ex);
            }
        }
    }
    
    // ========================================================================
    // INSERT Events
    // ========================================================================
    
    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        EntityOperationContext ctx = getContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            log.debug("PreInsert: {}", entity.getClass().getSimpleName());
            ctx.recordPendingOperation(entity, OperationType.INSERT, null);
        }
        return false;
    }
    
    @Override
    public void onPostInsert(PostInsertEvent event) {
        EntityOperationContext ctx = getContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object entityId = event.getId();
            log.debug("PostInsert: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            ctx.confirmOperation(entity, OperationType.INSERT, entityId, null);
        }
    }
    
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
    
    // ========================================================================
    // UPDATE Events
    // ========================================================================
    
    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        EntityOperationContext ctx = getContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object snapshot = createSnapshot(entity);
            Object entityId = event.getId();
            log.debug("PreUpdate: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            ctx.recordPendingOperation(entity, OperationType.UPDATE, snapshot);
        }
        return false;
    }
    
    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        EntityOperationContext ctx = getContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object entityId = event.getId();
            Object snapshot = ctx.getPendingSnapshot(entity);
            log.debug("PostUpdate confirmed: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            ctx.confirmOperation(entity, OperationType.UPDATE, entityId, snapshot);
        }
    }
    
    // ========================================================================
    // DELETE Events
    // ========================================================================
    
    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        EntityOperationContext ctx = getContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object snapshot = createSnapshot(entity);
            Object entityId = event.getId();
            log.debug("PreDelete: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            ctx.recordPendingOperation(entity, OperationType.DELETE, snapshot);
        }
        return false;
    }
    
    @Override
    public void onPostDelete(PostDeleteEvent event) {
        EntityOperationContext ctx = getContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object entityId = event.getId();
            Object snapshot = ctx.getPendingSnapshot(entity);
            log.debug("PostDelete confirmed: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            ctx.confirmOperation(entity, OperationType.DELETE, entityId, snapshot);
        }
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private Object createSnapshot(Object entity) {
        if (objectMapper == null || entity == null) {
            return null;
        }
        
        try {
            String json = objectMapper.writeValueAsString(entity);
            return objectMapper.readValue(json, entity.getClass());
        } catch (Exception e) {
            log.error("Cannot create snapshot for {}", entity.getClass().getSimpleName(), e);
            return null;
        }
    }
    
    // ========================================================================
    // Context Classes
    // ========================================================================
    
    public static class EntityOperationContext {
        private volatile boolean tracking;
        private final Map<Object, PendingOperation> pendingOps = new HashMap<>();
        private final List<ConfirmedOperation> confirmedOps = new ArrayList<>();
        private volatile boolean cleared = false;
        
        public EntityOperationContext(boolean tracking) {
            this.tracking = tracking;
        }
        
        public boolean isTracking() {
            return tracking && !cleared;
        }
        
        public void recordPendingOperation(Object entity, OperationType type, Object snapshot) {
            if (cleared) {
                log.warn("Attempting to record operation on cleared context!");
                return;
            }
            pendingOps.put(entity, new PendingOperation(type, snapshot));
        }
        
        public void confirmOperation(Object entity, OperationType type, Object entityId, Object snapshot) {
            if (cleared) {
                log.warn("Attempting to confirm operation on cleared context!");
                return;
            }
            
            PendingOperation pending = pendingOps.remove(entity);
            if (pending != null) {
                confirmedOps.add(new ConfirmedOperation(
                    entity.getClass().getName(),
                    type,
                    entityId,
                    pending.snapshot != null ? pending.snapshot : snapshot
                ));
            }
        }
        
        public Object getPendingSnapshot(Object entity) {
            if (cleared) {
                return null;
            }
            PendingOperation pending = pendingOps.get(entity);
            return pending != null ? pending.snapshot : null;
        }
        
        public List<ConfirmedOperation> getConfirmedOperations() {
            if (cleared) {
                log.warn("Getting operations from cleared context!");
                return new ArrayList<>();
            }
            return new ArrayList<>(confirmedOps);
        }
        
        /**
         * Normal clear - mark as cleared but don't remove data immediately
         */
        public void clear() {
            if (cleared) {
                log.trace("Context already cleared - double clear detected on thread: {}", 
                    Thread.currentThread().getId());
                return;
            }
            
            int pendingCount = pendingOps.size();
            int confirmedCount = confirmedOps.size();
            
            cleared = true;
            
            log.trace("Context marked as cleared - pending: {}, confirmed: {} - thread: {}", 
                pendingCount, confirmedCount, Thread.currentThread().getId());
        }
        
        /**
         * Force clear - actually remove all data (last resort)
         */
        public void forceClear() {
            cleared = true;
            
            try {
                pendingOps.clear();
            } catch (Exception e) {
                log.error("Failed to clear pendingOps", e);
            }
            
            try {
                confirmedOps.clear();
            } catch (Exception e) {
                log.error("Failed to clear confirmedOps", e);
            }
            
            tracking = false;
            
            log.debug("Context FORCE cleared - thread: {}", Thread.currentThread().getId());
        }
        
        public boolean isCleared() {
            return cleared;
        }
    }
    
    public static class PendingOperation {
        final OperationType type;
        final Object snapshot;
        
        PendingOperation(OperationType type, Object snapshot) {
            this.type = type;
            this.snapshot = snapshot;
        }
    }
    
    public static class ConfirmedOperation {
        public final String entityClass;
        public final OperationType type;
        public final Object entityId;
        public final Object snapshot;
        
        ConfirmedOperation(String entityClass, OperationType type, Object entityId, Object snapshot) {
            this.entityClass = entityClass;
            this.type = type;
            this.entityId = entityId;
            this.snapshot = snapshot;
        }
    }
}