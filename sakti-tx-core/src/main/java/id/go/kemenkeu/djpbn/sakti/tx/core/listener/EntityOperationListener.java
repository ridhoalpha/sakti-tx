package id.go.kemenkeu.djpbn.sakti.tx.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.Id;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hibernate Event Listener untuk automatic tracking
 * Implements Hibernate event interfaces untuk programmatic registration
 * 
 * ENHANCED VERSION:
 * - ThreadLocal cleanup verification
 * - Better error handling
 * - Memory leak detection
 */
public class EntityOperationListener implements 
        PreInsertEventListener,
        PostInsertEventListener,
        PreUpdateEventListener,
        PostUpdateEventListener,
        PreDeleteEventListener,
        PostDeleteEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(EntityOperationListener.class);
    private static final ThreadLocal<EntityOperationContext> CONTEXT = new ThreadLocal<>();
    private static ObjectMapper objectMapper;
    
    public static void setObjectMapper(ObjectMapper mapper) {
        objectMapper = mapper;
    }
    
    public static EntityOperationContext getContext() {
        return CONTEXT.get();
    }
    
    public static void setContext(EntityOperationContext context) {
        CONTEXT.set(context);
    }
    
    /**
     * ENHANCED: Clear context dengan verification dan force cleanup
     */
    public static void clearContext() {
        try {
            // Step 1: Get and clear context data
            EntityOperationContext ctx = CONTEXT.get();
            
            if (ctx != null) {
                // Clear internal data first
                ctx.clear();
                log.trace("Context data cleared - thread: {}", Thread.currentThread().getId());
            } else {
                log.trace("Context already null - thread: {}", Thread.currentThread().getId());
            }
            
            // Step 2: Remove ThreadLocal
            CONTEXT.remove();
            log.trace("ThreadLocal.remove() called - thread: {}", Thread.currentThread().getId());
            
            // Step 3: CRITICAL VERIFICATION
            // Sometimes ThreadLocal.remove() doesn't work properly in certain JVM scenarios
            EntityOperationContext afterRemove = CONTEXT.get();
            
            if (afterRemove != null) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("CRITICAL: ThreadLocal NOT cleared after remove()!");
                log.error("Thread: {} ({})", 
                    Thread.currentThread().getId(),
                    Thread.currentThread().getName());
                log.error("Context still exists after remove: {}", afterRemove);
                log.error("This should NEVER happen!");
                log.error("═══════════════════════════════════════════════════════════");
                
                // Step 4: Force cleanup strategies
                
                // Strategy 1: Set to null explicitly
                CONTEXT.set(null);
                log.warn("Forced set to null - thread: {}", Thread.currentThread().getId());
                
                // Strategy 2: Try remove again
                CONTEXT.remove();
                log.warn("Called remove() again - thread: {}", Thread.currentThread().getId());
                
                // Strategy 3: Final verification
                EntityOperationContext finalCheck = CONTEXT.get();
                if (finalCheck != null) {
                    log.error("═══════════════════════════════════════════════════════════");
                    log.error("FATAL: Cannot clear ThreadLocal even after multiple attempts!");
                    log.error("Thread: {} ({})", 
                        Thread.currentThread().getId(),
                        Thread.currentThread().getName());
                    log.error("JVM or ThreadLocal implementation issue detected");
                    log.error("═══════════════════════════════════════════════════════════");
                    
                    // Last resort - clear the context data at least
                    if (finalCheck != null) {
                        finalCheck.clear();
                    }
                } else {
                    log.info("✓ ThreadLocal cleared successfully after retry - thread: {}", 
                        Thread.currentThread().getId());
                }
            } else {
                log.trace("✓ ThreadLocal cleared successfully - thread: {}", 
                    Thread.currentThread().getId());
            }
            
        } catch (Exception e) {
            log.error("Exception during clearContext - thread: {}", 
                Thread.currentThread().getId(), e);
            
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
        EntityOperationContext ctx = CONTEXT.get();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            log.debug("PreInsert: {}", entity.getClass().getSimpleName());
            ctx.recordPendingOperation(entity, OperationType.INSERT, null);
        }
        return false;
    }
    
    @Override
    public void onPostInsert(PostInsertEvent event) {
        EntityOperationContext ctx = CONTEXT.get();
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
        EntityOperationContext ctx = CONTEXT.get();
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
        EntityOperationContext ctx = CONTEXT.get();
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
        EntityOperationContext ctx = CONTEXT.get();
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
        EntityOperationContext ctx = CONTEXT.get();
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
    
    private Object extractEntityId(Object entity) {
        try {
            for (Field field : entity.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    return field.get(entity);
                }
            }
            
            try {
                return entity.getClass().getMethod("getId").invoke(entity);
            } catch (NoSuchMethodException ignored) {}
            
        } catch (Exception e) {
            log.warn("Cannot extract entity ID from {}", entity.getClass().getSimpleName());
        }
        return null;
    }
    
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
        private boolean tracking;
        private final Map<Object, PendingOperation> pendingOps = new HashMap<>();
        private final List<ConfirmedOperation> confirmedOps = new ArrayList<>();
        private boolean cleared = false;
        
        public EntityOperationContext(boolean tracking) {
            this.tracking = tracking;
        }
        
        public boolean isTracking() {
            return tracking;
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
        
        public void clear() {
            if (cleared) {
                log.warn("Context already cleared - double clear detected on thread: {}", 
                    Thread.currentThread().getId());
                return;
            }
            
            int pendingCount = pendingOps.size();
            int confirmedCount = confirmedOps.size();
            
            pendingOps.clear();
            confirmedOps.clear();
            cleared = true;
            
            log.trace("Context cleared - pending: {}, confirmed: {} - thread: {}", 
                pendingCount, confirmedCount, Thread.currentThread().getId());
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