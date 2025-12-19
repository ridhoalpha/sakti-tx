package id.go.kemenkeu.djpbn.sakti.tx.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SECURITY FIX 2: Strong Reference Snapshot
 * 
 * CHANGED:
 * - WeakReference removed (GC could delete critical snapshot data)
 * - Use strong references with explicit cleanup
 * - Snapshot immediately persisted to Redis via TransactionLogManager
 * 
 * @version 1.0.3-SECURITY-FIX
 */
public class EntityOperationListener implements 
        PreInsertEventListener,
        PostInsertEventListener,
        PreUpdateEventListener,
        PostUpdateEventListener,
        PreDeleteEventListener,
        PostDeleteEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(EntityOperationListener.class);
    
    // ═══════════════════════════════════════════════════════════════
    // SECURITY FIX: Strong Reference (no WeakReference)
    // ═══════════════════════════════════════════════════════════════
    private static final ThreadLocal<EntityOperationContext> CONTEXT = 
        ThreadLocal.withInitial(() -> null);
    
    private static ObjectMapper objectMapper;
    
    public static void setObjectMapper(ObjectMapper mapper) {
        objectMapper = mapper;
    }
    
    public static EntityOperationContext getContext() {
        EntityOperationContext ctx = CONTEXT.get();
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
            CONTEXT.set(context);
        }
    }
    
    /**
     * ENHANCED: Multi-strategy cleanup with verification
     */
    public static void clearContext() {
        long threadId = Thread.currentThread().getId();
        
        try {
            // Strategy 1: Get and clear context data
            EntityOperationContext ctx = CONTEXT.get();
            if (ctx != null) {
                ctx.forceCleanData();  // Clear data immediately
                log.trace("Context data cleared - thread: {}", threadId);
            }
            
            // Strategy 2: Remove ThreadLocal (PRIMARY CLEANUP)
            CONTEXT.remove();
            log.trace("ThreadLocal.remove() called - thread: {}", threadId);
            
            // Strategy 3: VERIFICATION
            EntityOperationContext afterRemove = CONTEXT.get();
            
            if (afterRemove != null) {
                
                // Emergency cleanup
                afterRemove.forceCleanData();
                CONTEXT.remove();
                CONTEXT.set(null);
                CONTEXT.remove();
                
                // Final check
                EntityOperationContext finalCheck = CONTEXT.get();
                if (finalCheck != null) {
                    log.error("FATAL: Cannot clear ThreadLocal after multiple attempts!");
                }
            } else {
                log.trace("ThreadLocal cleared successfully - thread: {}", threadId);
            }
            
        } catch (Exception e) {
            log.error("Exception during clearContext - thread: {}", threadId, e);
            
            // Emergency cleanup
            try {
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
    
    /**
     * Create snapshot dengan deep clone
     * CRITICAL: Harus menghasilkan snapshot yang independent
     */
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
    
    /**
     * SECURITY FIX: Strong reference context
     */
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
         * Mark as cleared (lazy cleanup)
         */
        public void clear() {
            if (cleared) {
                log.trace("Context already cleared - thread: {}", 
                    Thread.currentThread().getId());
                return;
            }
            
            cleared = true;
            log.trace("Context marked as cleared - thread: {}", 
                Thread.currentThread().getId());
        }
        
        /**
         * SECURITY FIX: Force clean all data immediately
         */
        public void forceCleanData() {
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
            
            log.debug("Context data FORCE cleaned - thread: {}", 
                Thread.currentThread().getId());
        }
        
        public boolean isCleared() {
            return cleared;
        }
    }
    
    public static class PendingOperation {
        final OperationType type;
        final Object snapshot;  // Strong reference
        
        PendingOperation(OperationType type, Object snapshot) {
            this.type = type;
            this.snapshot = snapshot;
        }
    }
    
    public static class ConfirmedOperation {
        public final String entityClass;
        public final OperationType type;
        public final Object entityId;
        public final Object snapshot;  // Strong reference
        
        ConfirmedOperation(String entityClass, OperationType type, Object entityId, Object snapshot) {
            this.entityClass = entityClass;
            this.type = type;
            this.entityId = entityId;
            this.snapshot = snapshot;
        }
    }
}