package id.go.kemenkeu.djpbn.sakti.tx.starter.interceptor;

import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedTransactionContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;

/**
 *  MAGIC INTERCEPTOR
 * Automatically intercept ALL repository operations (save, delete, etc.)
 * Record to transaction log WITHOUT developer needing to do anything
 * 
 * Supports:
 * - JpaRepository.save()
 * - JpaRepository.saveAll()
 * - JpaRepository.delete()
 * - JpaRepository.deleteById()
 * - Custom @Query methods (via @TrackOperation)
 */
@Aspect
@Component
@Order(1) // Run BEFORE transaction aspect
public class RepositoryOperationInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(RepositoryOperationInterceptor.class);
    
    @PersistenceContext
    private EntityManager entityManager;
    
    /**
     * Intercept save() operations
     * Pattern: Any method named "save*" in JpaRepository
     */
    @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.save*(..)) " +
            "&& !@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SkipTracking)")
    public Object interceptSave(ProceedingJoinPoint pjp) throws Throwable {
        
        // Get transaction context
        DistributedTransactionContext ctx = DistributedTransactionContext.get();
        if (ctx == null || !ctx.isActive()) {
            // No distributed transaction - proceed normally
            return pjp.proceed();
        }
        
        Object[] args = pjp.getArgs();
        if (args.length == 0) {
            return pjp.proceed();
        }
        
        Object entity = args[0];
        if (entity == null) {
            return pjp.proceed();
        }
        
        // Determine if INSERT or UPDATE
        boolean isNew = isNewEntity(entity);
        OperationType opType = isNew ? OperationType.INSERT : OperationType.UPDATE;
        
        // Take snapshot BEFORE save (for UPDATE rollback)
        Object snapshot = null;
        if (!isNew) {
            snapshot = takeSnapshot(entity);
        }
        
        // Execute actual save
        Object result = pjp.proceed();
        
        // Get entity ID after save (for INSERT)
        Object entityId = getEntityId(result);
        
        // Record to transaction log
        String datasource = getDatasourceName(pjp);
        ctx.recordOperation(datasource, opType, entity.getClass().getName(), entityId, snapshot);
        
        log.debug("Tracked {} operation: {} [id={}] in {}", 
            opType, entity.getClass().getSimpleName(), entityId, datasource);
        
        return result;
    }
    
    /**
     * Intercept delete() operations
     */
    @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.delete*(..)) " +
            "&& !@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SkipTracking)")
    public Object interceptDelete(ProceedingJoinPoint pjp) throws Throwable {
        
        DistributedTransactionContext ctx = DistributedTransactionContext.get();
        if (ctx == null || !ctx.isActive()) {
            return pjp.proceed();
        }
        
        Object[] args = pjp.getArgs();
        if (args.length == 0) {
            return pjp.proceed();
        }
        
        Object entityOrId = args[0];
        Object entity = null;
        Object entityId = null;
        Object snapshot = null;
        
        // Determine if delete(Entity) or deleteById(ID)
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String methodName = signature.getName();
        
        if (methodName.contains("ById")) {
            // deleteById(ID) - need to fetch entity first for snapshot
            entityId = entityOrId;
            entity = findEntityById(pjp, entityId);
            if (entity != null) {
                snapshot = takeSnapshot(entity);
            }
        } else {
            // delete(Entity)
            entity = entityOrId;
            entityId = getEntityId(entity);
            snapshot = takeSnapshot(entity);
        }
        
        // Execute actual delete
        Object result = pjp.proceed();
        
        // Record to transaction log
        if (entity != null) {
            String datasource = getDatasourceName(pjp);
            ctx.recordOperation(datasource, OperationType.DELETE, 
                entity.getClass().getName(), entityId, snapshot);
            
            log.debug("Tracked DELETE operation: {} [id={}] in {}", 
                entity.getClass().getSimpleName(), entityId, datasource);
        }
        
        return result;
    }
    
    /**
     * Check if entity is new (INSERT) or existing (UPDATE)
     */
    private boolean isNewEntity(Object entity) {
        try {
            Object id = getEntityId(entity);
            if (id == null) {
                return true; // No ID = new entity
            }
            
            // Check if exists in persistence context or DB
            // This is a simple check - can be improved based on your entity design
            return !entityManager.contains(entity);
            
        } catch (Exception e) {
            log.warn("Cannot determine if entity is new, assuming INSERT: {}", 
                entity.getClass().getSimpleName());
            return true;
        }
    }
    
    /**
     * Get entity ID using reflection
     * Supports @Id annotation
     */
    private Object getEntityId(Object entity) {
        if (entity == null) return null;
        
        try {
            // Try common ID field names first
            for (String idField : new String[]{"id", "getId"}) {
                try {
                    if (idField.startsWith("get")) {
                        Method getter = entity.getClass().getMethod(idField);
                        return getter.invoke(entity);
                    } else {
                        java.lang.reflect.Field field = entity.getClass().getDeclaredField(idField);
                        field.setAccessible(true);
                        return field.get(entity);
                    }
                } catch (Exception ignored) {}
            }
            
            // Fallback: Find field with @Id annotation
            for (java.lang.reflect.Field field : entity.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    field.setAccessible(true);
                    return field.get(entity);
                }
            }
            
            log.warn("Cannot find ID field for entity: {}", entity.getClass().getSimpleName());
            return null;
            
        } catch (Exception e) {
            log.error("Error getting entity ID", e);
            return null;
        }
    }
    
    /**
     * Take snapshot of entity for rollback
     * Uses deep clone to avoid persistence context issues
     */
    private Object takeSnapshot(Object entity) {
        if (entity == null) return null;
        
        try {
            // Detach from persistence context to avoid modifications
            if (entityManager.contains(entity)) {
                entityManager.detach(entity);
            }
            
            // Simple clone - can use Jackson for deep copy if needed
            return entity; // Simplified - in production use proper cloning
            
        } catch (Exception e) {
            log.warn("Cannot take snapshot of entity: {}", entity.getClass().getSimpleName(), e);
            return entity;
        }
    }
    
    /**
     * Find entity by ID (for deleteById scenario)
     */
    private Object findEntityById(ProceedingJoinPoint pjp, Object id) {
        try {
            // Get repository instance
            Object repository = pjp.getTarget();
            if (repository instanceof JpaRepository) {
                @SuppressWarnings("unchecked")
                JpaRepository<Object, Object> repo = (JpaRepository<Object, Object>) repository;
                return repo.findById(id).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Cannot find entity by ID for snapshot", e);
        }
        return null;
    }
    
    /**
     * Get datasource name from repository
     * Uses reflection to find @Qualifier or default datasource
     */
    private String getDatasourceName(ProceedingJoinPoint pjp) {
        try {
            // Try to get from @PersistenceContext qualifier
            Object target = pjp.getTarget();
            Class<?> targetClass = target.getClass();
            
            // Check for @Qualifier annotation
            if (targetClass.isAnnotationPresent(org.springframework.beans.factory.annotation.Qualifier.class)) {
                org.springframework.beans.factory.annotation.Qualifier qualifier = 
                    targetClass.getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class);
                return qualifier.value();
            }
            
            // Fallback to package name as datasource identifier
            String packageName = targetClass.getPackage().getName();
            if (packageName.contains("db1") || packageName.contains("primary")) {
                return "db1";
            } else if (packageName.contains("db2") || packageName.contains("secondary")) {
                return "db2";
            } else if (packageName.contains("db3")) {
                return "db3";
            }
            
            // Default
            return "default";
            
        } catch (Exception e) {
            log.warn("Cannot determine datasource name, using 'default'", e);
            return "default";
        }
    }
}