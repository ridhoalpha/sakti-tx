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
    
    public static void clearContext() {
        EntityOperationContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.clear();
        }
        CONTEXT.remove();
        
        // Sanity check - verify it's actually cleared
        if (CONTEXT.get() != null) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("MEMORY LEAK DETECTED: ThreadLocal context was not cleared!");
            log.error("This can cause cross-request contamination in thread pools");
            log.error("═══════════════════════════════════════════════════════════");
            // Force remove
            CONTEXT.remove();
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
    
    public boolean requiresPostCommitHanding(EntityPersister persister) {
        return false;
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
        
        public EntityOperationContext(boolean tracking) {
            this.tracking = tracking;
        }
        
        public boolean isTracking() {
            return tracking;
        }
        
        public void recordPendingOperation(Object entity, OperationType type, Object snapshot) {
            pendingOps.put(entity, new PendingOperation(type, snapshot));
        }
        
        public void confirmOperation(Object entity, OperationType type, Object entityId, Object snapshot) {
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
            PendingOperation pending = pendingOps.get(entity);
            return pending != null ? pending.snapshot : null;
        }
        
        public List<ConfirmedOperation> getConfirmedOperations() {
            return new ArrayList<>(confirmedOps);
        }
        
        public void clear() {
            pendingOps.clear();
            confirmedOps.clear();
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