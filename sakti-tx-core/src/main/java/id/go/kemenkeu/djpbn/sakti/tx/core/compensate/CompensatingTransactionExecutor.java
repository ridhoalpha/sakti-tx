package id.go.kemenkeu.djpbn.sakti.tx.core.compensate;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.util.*;

/**
 * COMPENSATING TRANSACTION EXECUTOR - PRODUCTION VERSION
 * 
 * HANDLES ALL SCENARIOS:
 * 1. Entity operations (save, delete)
 * 2. Native queries (@Query with nativeQuery=true)
 * 3. JPQL bulk operations (UPDATE/DELETE)
 * 4. Stored procedures (@Procedure)
 * 5. Soft deletes (UPDATE set deleted=1)
 * 6. Batch operations
 * 7. executeUpdate() calls
 * 
 * LIMITATIONS:
 * - Cannot compensate: DDL operations, sequence changes, non-logged operations
 * - Stored procedures: needs inverse procedure or manual snapshot
 * - Bulk operations: needs pre-execution snapshot for affected rows
 */
public class CompensatingTransactionExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(CompensatingTransactionExecutor.class);
    
    private final Map<String, EntityManager> entityManagers;
    private final ObjectMapper objectMapper;
    
    public CompensatingTransactionExecutor(
            Map<String, EntityManager> entityManagers,
            ObjectMapper objectMapper) {
        this.entityManagers = entityManagers;
        this.objectMapper = objectMapper;
        
        log.info("CompensatingTransactionExecutor initialized with {} EntityManagers", 
            entityManagers.size());
    }
    
    /**
     * Execute rollback untuk entire transaction
     * Operations di-rollback dalam REVERSE order
     */
    public void rollback(TransactionLog txLog) {
        log.warn("Starting rollback for transaction: {}", txLog.getTxId());
        
        List<OperationLog> operations = txLog.getOperationsInReverseOrder();
        int successCount = 0;
        int failureCount = 0;
        int skippedCount = 0;
        
        for (OperationLog op : operations) {
            if (op.isCompensated()) {
                log.debug("Skip already compensated: {} [id={}]", 
                    op.getEntityClass(), op.getEntityId());
                skippedCount++;
                continue;
            }
            
            try {
                compensateOperation(op);
                op.setCompensated(true);
                successCount++;
                
                log.info(" Compensated: {} {} [id={}]", 
                    op.getOperationType(), op.getEntityClass(), op.getEntityId());
                
            } catch (Exception e) {
                op.setCompensationError(e.getMessage());
                failureCount++;
                
                log.error(" Compensation FAILED: {} {} [id={}] - {}", 
                    op.getOperationType(), op.getEntityClass(), 
                    op.getEntityId(), e.getMessage(), e);
            }
        }
        
        log.warn("Rollback completed: {} success, {} failed, {} skipped", 
            successCount, failureCount, skippedCount);
        
        if (failureCount > 0) {
            throw new CompensationException(
                String.format("Rollback partially failed: %d/%d operations failed", 
                    failureCount, operations.size()));
        }
    }
    
    /**
     * Compensate single operation
     */
    private void compensateOperation(OperationLog op) throws Exception {
        
        EntityManager em = getEntityManager(op.getDatasource());
        
        // Determine compensation strategy based on operation type
        switch (op.getOperationType()) {
            case INSERT:
                compensateInsert(em, op);
                break;
                
            case UPDATE:
                compensateUpdate(em, op);
                break;
                
            case DELETE:
                compensateDelete(em, op);
                break;
                
            case BULK_UPDATE:
                compensateBulkUpdate(em, op);
                break;
                
            case BULK_DELETE:
                compensateBulkDelete(em, op);
                break;
                
            case NATIVE_QUERY:
                compensateNativeQuery(em, op);
                break;
                
            case STORED_PROCEDURE:
                compensateStoredProcedure(em, op);
                break;
                
            default:
                log.warn(" Unknown operation type: {} - skipping", op.getOperationType());
        }
    }
    
    /**
     * Compensate INSERT → DELETE the record
     */
    private void compensateInsert(EntityManager em, OperationLog op) throws Exception {
        log.debug("DELETE inserted record: {} [id={}]", op.getEntityClass(), op.getEntityId());
        
        Class<?> entityClass = Class.forName(op.getEntityClass());
        Object entity = em.find(entityClass, op.getEntityId());
        
        if (entity == null) {
            log.warn("Entity already deleted: {} [id={}] - idempotent", 
                op.getEntityClass(), op.getEntityId());
            return;
        }
        
        em.remove(entity);
        em.flush();
        
        log.info("Deleted: {} [id={}]", op.getEntityClass(), op.getEntityId());
    }
    
    /**
     * Compensate UPDATE → Restore to original snapshot
     */
    private void compensateUpdate(EntityManager em, OperationLog op) throws Exception {
        log.debug("RESTORE updated record: {} [id={}]", op.getEntityClass(), op.getEntityId());
        
        if (op.getSnapshot() == null) {
            log.error("No snapshot for UPDATE rollback: {} [id={}]", 
                op.getEntityClass(), op.getEntityId());
            throw new IllegalStateException("Cannot rollback UPDATE without snapshot");
        }
        
        Class<?> entityClass = Class.forName(op.getEntityClass());
        Object snapshotEntity = objectMapper.convertValue(op.getSnapshot(), entityClass);
        
        clearVersionField(snapshotEntity);
        
        Object merged = em.merge(snapshotEntity);
        em.flush();
        
        log.info("Restored: {} [id={}]", op.getEntityClass(), op.getEntityId());
    }
    
    /**
     * Compensate DELETE → Re-insert the deleted record
     */
    private void compensateDelete(EntityManager em, OperationLog op) throws Exception {
        log.debug("RE-INSERT deleted record: {} [id={}]", op.getEntityClass(), op.getEntityId());
        
        if (op.getSnapshot() == null) {
            log.error("No snapshot for DELETE rollback: {} [id={}]", 
                op.getEntityClass(), op.getEntityId());
            throw new IllegalStateException("Cannot rollback DELETE without snapshot");
        }
        
        Class<?> entityClass = Class.forName(op.getEntityClass());
        Object existing = em.find(entityClass, op.getEntityId());
        
        if (existing != null) {
            log.warn("Entity already exists: {} [id={}] - idempotent", 
                op.getEntityClass(), op.getEntityId());
            return;
        }
        
        Object snapshotEntity = objectMapper.convertValue(op.getSnapshot(), entityClass);
        clearVersionField(snapshotEntity);
        
        em.persist(snapshotEntity);
        em.flush();
        
        log.info("Re-inserted: {} [id={}]", op.getEntityClass(), op.getEntityId());
    }
    
    /**
     * Compensate BULK UPDATE
     * 
     * Example: UPDATE Account SET balance = balance + 100 WHERE region = 'ASIA'
     * Compensation: Restore each affected row to snapshot
     */
    private void compensateBulkUpdate(EntityManager em, OperationLog op) throws Exception {
        log.debug("Compensating BULK UPDATE: {}", op.getAdditionalInfo());
        
        if (op.getAffectedEntities() == null || op.getAffectedEntities().isEmpty()) {
            log.warn(" No affected entities snapshot - cannot compensate bulk update");
            throw new IllegalStateException("Bulk update compensation requires affected entities snapshot");
        }
        
        Class<?> entityClass = Class.forName(op.getEntityClass());
        int restoredCount = 0;
        
        // Restore each affected entity
        for (Map<String, Object> snapshot : op.getAffectedEntities()) {
            try {
                Object entity = objectMapper.convertValue(snapshot, entityClass);
                clearVersionField(entity);
                em.merge(entity);
                restoredCount++;
            } catch (Exception e) {
                log.error("Failed to restore entity: {}", snapshot, e);
            }
        }
        
        em.flush();
        log.info("Bulk update compensated: {} entities restored", restoredCount);
    }
    
    /**
     * Compensate BULK DELETE
     * 
     * Example: DELETE FROM Account WHERE balance = 0
     * Compensation: Re-insert all deleted rows
     */
    private void compensateBulkDelete(EntityManager em, OperationLog op) throws Exception {
        log.debug("Compensating BULK DELETE: {}", op.getAdditionalInfo());
        
        if (op.getAffectedEntities() == null || op.getAffectedEntities().isEmpty()) {
            log.warn(" No affected entities snapshot - cannot compensate bulk delete");
            throw new IllegalStateException("Bulk delete compensation requires affected entities snapshot");
        }
        
        Class<?> entityClass = Class.forName(op.getEntityClass());
        int reinsertedCount = 0;
        
        // Re-insert each deleted entity
        for (Map<String, Object> snapshot : op.getAffectedEntities()) {
            try {
                Object entity = objectMapper.convertValue(snapshot, entityClass);
                clearVersionField(entity);
                em.persist(entity);
                reinsertedCount++;
            } catch (Exception e) {
                log.error("Failed to re-insert entity: {}", snapshot, e);
            }
        }
        
        em.flush();
        log.info("Bulk delete compensated: {} entities re-inserted", reinsertedCount);
    }
    
    /**
     * Compensate NATIVE QUERY
     * 
     * Examples:
     * - UPDATE account SET balance = balance + 100 WHERE id = ?
     * - UPDATE account SET deleted = 1 WHERE id = ? (soft delete)
     * - INSERT INTO audit_log SELECT * FROM temp_audit
     * 
     * Strategy: Execute inverse query or restore from snapshot
     */
    private void compensateNativeQuery(EntityManager em, OperationLog op) throws Exception {
        log.debug("Compensating NATIVE QUERY: {}", op.getAdditionalInfo());
        
        // Check if inverse query provided
        if (op.getInverseQuery() != null && !op.getInverseQuery().isEmpty()) {
            log.debug("Executing inverse query: {}", op.getInverseQuery());
            
            Query query = em.createNativeQuery(op.getInverseQuery());
            
            // Bind parameters if any
            if (op.getQueryParameters() != null) {
                for (Map.Entry<String, Object> param : op.getQueryParameters().entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            int updated = query.executeUpdate();
            em.flush();
            
            log.info("Native query compensated: {} rows affected", updated);
            
        } else if (op.getSnapshot() != null) {
            // Fallback: restore single entity from snapshot
            log.debug("Using snapshot fallback for native query compensation");
            compensateUpdate(em, op);
            
        } else {
            log.error(" Cannot compensate native query - no inverse query or snapshot");
            throw new IllegalStateException(
                "Native query compensation requires inverse query or snapshot");
        }
    }
    
    /**
     * Compensate STORED PROCEDURE
     * 
     * Examples:
     * - CALL update_account_balance(?, ?)
     * - EXEC sp_process_transaction ?
     * 
     * Strategy:
     * 1. Execute inverse procedure if provided
     * 2. Otherwise restore from snapshot
     */
    private void compensateStoredProcedure(EntityManager em, OperationLog op) throws Exception {
        log.debug("Compensating STORED PROCEDURE: {}", op.getAdditionalInfo());
        
        if (op.getInverseProcedure() != null && !op.getInverseProcedure().isEmpty()) {
            log.debug("Executing inverse procedure: {}", op.getInverseProcedure());
            
            Query query = em.createNativeQuery(
                "CALL " + op.getInverseProcedure());
            
            if (op.getQueryParameters() != null) {
                for (Map.Entry<String, Object> param : op.getQueryParameters().entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            query.executeUpdate();
            em.flush();
            
            log.info("Stored procedure compensated");
            
        } else if (op.getAffectedEntities() != null && !op.getAffectedEntities().isEmpty()) {
            // Restore affected entities
            log.debug("Restoring affected entities for procedure compensation");
            compensateBulkUpdate(em, op);
            
        } else {
            log.error(" Cannot compensate stored procedure - no inverse procedure or snapshots");
            throw new IllegalStateException(
                "Stored procedure compensation requires inverse procedure or affected entities snapshot");
        }
    }
    
    /**
     * Get EntityManager untuk datasource
     */
    private EntityManager getEntityManager(String datasource) {
        EntityManager em = entityManagers.get(datasource);
        
        if (em == null) {
            throw new IllegalStateException(
                "No EntityManager found for datasource: " + datasource + 
                ". Available: " + entityManagers.keySet());
        }
        
        return em;
    }
    
    /**
     * Clear @Version field untuk avoid optimistic locking exception
     */
    private void clearVersionField(Object entity) {
        try {
            Class<?> clazz = entity.getClass();
            
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Version.class)) {
                    field.setAccessible(true);
                    field.set(entity, null);
                    log.debug("Cleared @Version field: {}", field.getName());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clear version field: {}", e.getMessage());
        }
    }
    
    public static class CompensationException extends RuntimeException {
        public CompensationException(String message) {
            super(message);
        }
        
        public CompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}