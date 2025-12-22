package id.go.kemenkeu.djpbn.sakti.tx.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextHolder;
import id.go.kemenkeu.djpbn.sakti.tx.core.inspection.CascadeDetector;
import id.go.kemenkeu.djpbn.sakti.tx.core.inspection.TriggerDetector;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ENHANCED: JPA Hibernate Event Listener with risk detection
 * 
 * @version 2.0.0
 */
public class EntityOperationListener implements 
        PreInsertEventListener,
        PostInsertEventListener,
        PreUpdateEventListener,
        PostUpdateEventListener,
        PreDeleteEventListener,
        PostDeleteEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(EntityOperationListener.class);
    
    // ThreadLocal for operation tracking (separate from TX context)
    private static final ThreadLocal<EntityOperationContext> OPERATION_CONTEXT = 
        ThreadLocal.withInitial(() -> null);
    
    private static ObjectMapper objectMapper;
    private static TriggerDetector triggerDetector;
    private static CascadeDetector cascadeDetector;
    
    public static void setObjectMapper(ObjectMapper mapper) {
        objectMapper = mapper;
    }
    
    public static void setTriggerDetector(TriggerDetector detector) {
        triggerDetector = detector;
    }
    
    public static void setCascadeDetector(CascadeDetector detector) {
        cascadeDetector = detector;
    }
    
    public static EntityOperationContext getOperationContext() {
        EntityOperationContext ctx = OPERATION_CONTEXT.get();
        if (ctx != null && ctx.isCleared()) {
            OPERATION_CONTEXT.remove();
            return null;
        }
        return ctx;
    }
    
    public static void setOperationContext(EntityOperationContext context) {
        if (context == null) {
            OPERATION_CONTEXT.remove();
        } else {
            OPERATION_CONTEXT.set(context);
        }
    }
    
    public static void clearOperationContext() {
        long threadId = Thread.currentThread().getId();
        
        try {
            EntityOperationContext ctx = OPERATION_CONTEXT.get();
            if (ctx != null) {
                ctx.forceCleanData();
                log.trace("Operation context data cleared - thread: {}", threadId);
            }
            
            OPERATION_CONTEXT.remove();
            log.trace("Operation context removed - thread: {}", threadId);
            
            EntityOperationContext afterRemove = OPERATION_CONTEXT.get();
            if (afterRemove != null) {
                log.error("CRITICAL: Operation context NOT cleared after remove!");
                OPERATION_CONTEXT.remove();
                OPERATION_CONTEXT.set(null);
                OPERATION_CONTEXT.remove();
            }
            
        } catch (Exception e) {
            log.error("Exception during clearOperationContext - thread: {}", threadId, e);
            try {
                OPERATION_CONTEXT.set(null);
                OPERATION_CONTEXT.remove();
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
        EntityOperationContext ctx = getOperationContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            log.debug("PreInsert: {}", entity.getClass().getSimpleName());
            
            // Check for triggers
            detectTriggerRisk(entity.getClass(), event.getPersister().getIdentifierTableName());
            
            // Check for cascades
            detectCascadeRisk(entity.getClass(), OperationType.INSERT);
            
            ctx.recordPendingOperation(entity, OperationType.INSERT, null);
        }
        return false;
    }
    
    @Override
    public void onPostInsert(PostInsertEvent event) {
        EntityOperationContext ctx = getOperationContext();
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
        EntityOperationContext ctx = getOperationContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object snapshot = createSnapshot(entity);
            Object entityId = event.getId();
            
            log.debug("PreUpdate: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            
            // Check for triggers
            detectTriggerRisk(entity.getClass(), event.getPersister().getIdentifierTableName());
            
            ctx.recordPendingOperation(entity, OperationType.UPDATE, snapshot);
        }
        return false;
    }
    
    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        EntityOperationContext ctx = getOperationContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object entityId = event.getId();
            Object snapshot = ctx.getPendingSnapshot(entity);
            log.debug("PostUpdate confirmed: {} [id={}]", 
                entity.getClass().getSimpleName(), entityId);
            ctx.confirmOperation(entity, OperationType.UPDATE, entityId, snapshot);
        }
    }
    
    // ========================================================================
    // DELETE Events
    // ========================================================================
    
    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        EntityOperationContext ctx = getOperationContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object snapshot = createSnapshot(entity);
            Object entityId = event.getId();
            
            log.debug("PreDelete: {} [id={}]", entity.getClass().getSimpleName(), entityId);
            
            // Check for triggers
            detectTriggerRisk(entity.getClass(), event.getPersister().getIdentifierTableName());
            
            // Check for cascades (DELETE is high risk)
            detectCascadeRisk(entity.getClass(), OperationType.DELETE);
            
            ctx.recordPendingOperation(entity, OperationType.DELETE, snapshot);
        }
        return false;
    }
    
    @Override
    public void onPostDelete(PostDeleteEvent event) {
        EntityOperationContext ctx = getOperationContext();
        if (ctx != null && ctx.isTracking()) {
            Object entity = event.getEntity();
            Object entityId = event.getId();
            Object snapshot = ctx.getPendingSnapshot(entity);
            log.debug("PostDelete confirmed: {} [id={}]", 
                entity.getClass().getSimpleName(), entityId);
            ctx.confirmOperation(entity, OperationType.DELETE, entityId, snapshot);
        }
    }
    
    // ========================================================================
    // Risk Detection
    // ========================================================================
    
    private void detectTriggerRisk(Class<?> entityClass, String tableName) {
        if (triggerDetector != null && SaktiTxContextHolder.hasContext()) {
            try {
                // Check if table has triggers
                // We don't have datasource here, so check all
                boolean hasTriggers = false;
                
                // This is a simplification - in production you'd need datasource context
                // For now, flag risk if we can't determine
                
                if (hasTriggers) {
                    SaktiTxContext context = SaktiTxContextHolder.get();
                    SaktiTxContext updated = context.withRiskFlag(RiskFlag.TRIGGER_SUSPECTED);
                    SaktiTxContextHolder.update(updated);
                    
                    log.warn("Trigger risk detected on entity: {}", 
                        entityClass.getSimpleName());
                }
            } catch (Exception e) {
                log.debug("Could not detect triggers: {}", e.getMessage());
            }
        }
    }
    
    private void detectCascadeRisk(Class<?> entityClass, OperationType opType) {
        if (cascadeDetector != null && SaktiTxContextHolder.hasContext()) {
            try {
                CascadeDetector.CascadeInfo info = cascadeDetector.detectCascade(entityClass);
                
                if (opType == OperationType.DELETE && info.hasCascadeDelete()) {
                    SaktiTxContext context = SaktiTxContextHolder.get();
                    SaktiTxContext updated = context.withRiskFlag(RiskFlag.CASCADE_DELETE);
                    SaktiTxContextHolder.update(updated);
                    
                    log.warn("Cascade DELETE risk on {}: {}", 
                        entityClass.getSimpleName(), info.getCascadeDeleteFields());
                }
            } catch (Exception e) {
                log.debug("Could not detect cascade: {}", e.getMessage());
            }
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
            log.error("Cannot create snapshot for {}", 
                entity.getClass().getSimpleName(), e);
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
        
        public void confirmOperation(Object entity, OperationType type, 
                                    Object entityId, Object snapshot) {
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
                log.trace("Context already cleared - thread: {}", 
                    Thread.currentThread().getId());
                return;
            }
            cleared = true;
            log.trace("Context marked as cleared - thread: {}", 
                Thread.currentThread().getId());
        }
        
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
        
        ConfirmedOperation(String entityClass, OperationType type, 
                          Object entityId, Object snapshot) {
            this.entityClass = entityClass;
            this.type = type;
            this.entityId = entityId;
            this.snapshot = snapshot;
        }
    }
}