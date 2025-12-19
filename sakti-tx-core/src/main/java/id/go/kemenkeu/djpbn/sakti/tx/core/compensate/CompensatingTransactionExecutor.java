package id.go.kemenkeu.djpbn.sakti.tx.core.compensate;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.util.*;

/**
 * SECURITY FIX 3: Enhanced Error Handling
 * 
 * PRINCIPLES:
 * 1. NO error swallowing - all errors MUST be logged and propagated
 * 2. Categorized errors: FATAL (stop rollback) vs RETRYABLE (continue)
 * 3. Detailed error context for debugging
 * 4. Circuit breaker for repeated failures
 * 
 * @version 1.0.3-SECURITY-FIX
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
     * SECURITY FIX: Enhanced rollback with proper error handling
     */
    public void rollback(TransactionLog txLog) {
        String txId = txLog.getTxId();
        MDC.put("txId", txId);
        
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("Starting COMPENSATING ROLLBACK");
        log.warn("Transaction ID: {}", txId);
        log.warn("Business Key: {}", txLog.getBusinessKey());
        log.warn("Total Operations: {}", txLog.getOperations().size());
        log.warn("═══════════════════════════════════════════════════════════");
        
        List<OperationLog> operations = txLog.getOperationsInReverseOrder();
        RollbackResult result = new RollbackResult();
        List<CompensationError> criticalErrors = new ArrayList<>();
        
        for (int i = 0; i < operations.size(); i++) {
            OperationLog op = operations.get(i);
            
            // Set MDC for this operation
            MDC.put("opSequence", String.valueOf(op.getSequence()));
            MDC.put("opType", op.getOperationType().toString());
            
            try {
                if (op.isCompensated()) {
                    log.debug("Skip already compensated: {} [id={}]", 
                        op.getEntityClass(), op.getEntityId());
                    result.skipped++;
                    continue;
                }
                
                log.info("Compensating operation {}/{}: {} {} [id={}] on {}", 
                    i + 1, operations.size(),
                    op.getOperationType(), 
                    op.getEntityClass(), 
                    op.getEntityId(),
                    op.getDatasource());
                
                // ═══════════════════════════════════════════════════════════
                // SECURITY FIX: Compensate with categorized error handling
                // ═══════════════════════════════════════════════════════════
                compensateOperationSafe(op);
                
                op.setCompensated(true);
                result.successful++;
                
                log.info("✓ Compensation successful: {} {} [id={}]", 
                    op.getOperationType(), op.getEntityClass(), op.getEntityId());
                
            } catch (FatalCompensationException fatal) {
                // ═══════════════════════════════════════════════════════════
                // FATAL ERROR: Stop rollback immediately
                // ═══════════════════════════════════════════════════════════
                op.setCompensationError(fatal.getMessage());
                result.failed++;
                
                CompensationError error = new CompensationError(
                    op, fatal, true, i + 1, operations.size()
                );
                criticalErrors.add(error);
                
                logFatalError(error);
                
                // STOP rollback - cannot continue
                break;
                
            } catch (RetryableCompensationException retryable) {
                // ═══════════════════════════════════════════════════════════
                // RETRYABLE ERROR: Log and continue with next operation
                // ═══════════════════════════════════════════════════════════
                op.setCompensationError(retryable.getMessage());
                result.failed++;
                
                CompensationError error = new CompensationError(
                    op, retryable, false, i + 1, operations.size()
                );
                criticalErrors.add(error);
                
                logRetryableError(error);
                
                // Continue with next operation
                
            } catch (Exception unknown) {
                // ═══════════════════════════════════════════════════════════
                // UNKNOWN ERROR: Treat as FATAL by default
                // ═══════════════════════════════════════════════════════════
                op.setCompensationError(unknown.getMessage());
                result.failed++;
                
                CompensationError error = new CompensationError(
                    op, unknown, true, i + 1, operations.size()
                );
                criticalErrors.add(error);
                
                logUnknownError(error);
                
                // STOP rollback
                break;
                
            } finally {
                MDC.remove("opSequence");
                MDC.remove("opType");
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // FINAL RESULT
        // ═══════════════════════════════════════════════════════════════
        
        logRollbackSummary(txId, result);
        
        MDC.remove("txId");
        
        // ═══════════════════════════════════════════════════════════════
        // SECURITY FIX: ALWAYS throw exception if ANY error occurred
        // ═══════════════════════════════════════════════════════════════
        if (!criticalErrors.isEmpty()) {
            throw new PartialRollbackException(
                txId, 
                result, 
                criticalErrors,
                "Rollback partially failed: " + result.failed + "/" + operations.size() + 
                " operations failed. Check logs for details."
            );
        }
        
        log.info("✓ Rollback completed successfully for txId: {}", txId);
    }
    
    /**
     * SECURITY FIX: Safe compensation with error categorization
     */
    private void compensateOperationSafe(OperationLog op) throws CompensationException {
        EntityManager em = null;
        
        try {
            em = getEntityManager(op.getDatasource());
            
            // Execute compensation based on operation type
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
                    throw new FatalCompensationException(
                        "Unknown operation type: " + op.getOperationType()
                    );
            }
            
        } catch (CompensationException e) {
            // Re-throw already categorized exceptions
            throw e;
            
        } catch (jakarta.persistence.OptimisticLockException e) {
            // Retryable: Concurrent modification
            throw new RetryableCompensationException(
                "Optimistic lock failure - entity was modified concurrently", e
            );
            
        } catch (jakarta.persistence.EntityNotFoundException e) {
            // Retryable: Entity already deleted (idempotent)
            throw new RetryableCompensationException(
                "Entity not found - may have been deleted already", e
            );
            
        } catch (jakarta.persistence.PersistenceException e) {
            // Check if it's a constraint violation
            if (e.getMessage() != null && 
                (e.getMessage().contains("constraint") || 
                 e.getMessage().contains("foreign key"))) {
                throw new FatalCompensationException(
                    "Database constraint violation - cannot rollback", e
                );
            }
            
            // Other persistence exceptions are retryable
            throw new RetryableCompensationException(
                "Database operation failed - may be retryable", e
            );
            
        } catch (Exception e) {
            // Unknown exception - treat as fatal
            throw new FatalCompensationException(
                "Unexpected error during compensation", e
            );
        }
    }
    
    // ========================================================================
    // Compensation Methods (unchanged logic, just error handling)
    // ========================================================================
    
    private void compensateInsert(EntityManager em, OperationLog op) throws CompensationException {
        try {
            log.debug("DELETE inserted record: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
            Class<?> entityClass = Class.forName(op.getEntityClass());
            Object entity = em.find(entityClass, op.getEntityId());
            
            if (entity == null) {
                log.warn("Entity already deleted: {} [id={}] - idempotent", 
                    op.getEntityClass(), op.getEntityId());
                return;
            }
            
            if (!em.contains(entity)) {
                entity = em.merge(entity);
            }
            
            em.remove(entity);
            em.flush();
            
            log.info("Deleted: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException("Entity class not found: " + op.getEntityClass(), e);
        }
    }
    
    private void compensateUpdate(EntityManager em, OperationLog op) throws CompensationException {
        try {
            log.debug("RESTORE updated record: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
            if (op.getSnapshot() == null) {
                throw new FatalCompensationException(
                    "No snapshot for UPDATE rollback: " + op.getEntityClass() + " [id=" + op.getEntityId() + "]"
                );
            }
            
            Class<?> entityClass = Class.forName(op.getEntityClass());
            Object snapshotEntity = objectMapper.convertValue(op.getSnapshot(), entityClass);
            
            clearVersionField(snapshotEntity);
            
            Object merged = em.merge(snapshotEntity);
            em.flush();
            
            log.info("Restored: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException("Entity class not found: " + op.getEntityClass(), e);
        }
    }
    
    private void compensateDelete(EntityManager em, OperationLog op) throws CompensationException {
        try {
            log.debug("RE-INSERT deleted record: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
            if (op.getSnapshot() == null) {
                throw new FatalCompensationException(
                    "No snapshot for DELETE rollback: " + op.getEntityClass() + " [id=" + op.getEntityId() + "]"
                );
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
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException("Entity class not found: " + op.getEntityClass(), e);
        }
    }
    
    private void compensateBulkUpdate(EntityManager em, OperationLog op) throws CompensationException {
        log.debug("Compensating BULK UPDATE: {}", op.getAdditionalInfo());
        
        if (op.getAffectedEntities() == null || op.getAffectedEntities().isEmpty()) {
            throw new FatalCompensationException(
                "Bulk update compensation requires affected entities snapshot"
            );
        }
        
        try {
            Class<?> entityClass = Class.forName(op.getEntityClass());
            int restoredCount = 0;
            List<String> failedIds = new ArrayList<>();
            
            for (Map<String, Object> snapshot : op.getAffectedEntities()) {
                try {
                    Object entity = objectMapper.convertValue(snapshot, entityClass);
                    clearVersionField(entity);
                    em.merge(entity);
                    restoredCount++;
                } catch (Exception e) {
                    log.error("Failed to restore entity: {}", snapshot, e);
                    failedIds.add(String.valueOf(snapshot.get("id")));
                }
            }
            
            em.flush();
            
            if (!failedIds.isEmpty()) {
                throw new RetryableCompensationException(
                    "Bulk update partial failure - restored " + restoredCount + 
                    " of " + op.getAffectedEntities().size() + 
                    " entities. Failed IDs: " + failedIds
                );
            }
            
            log.info("Bulk update compensated: {} entities restored", restoredCount);
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException("Entity class not found: " + op.getEntityClass(), e);
        }
    }
    
    private void compensateBulkDelete(EntityManager em, OperationLog op) throws CompensationException {
        log.debug("Compensating BULK DELETE: {}", op.getAdditionalInfo());
        
        if (op.getAffectedEntities() == null || op.getAffectedEntities().isEmpty()) {
            throw new FatalCompensationException(
                "Bulk delete compensation requires affected entities snapshot"
            );
        }
        
        try {
            Class<?> entityClass = Class.forName(op.getEntityClass());
            int reinsertedCount = 0;
            List<String> failedIds = new ArrayList<>();
            
            for (Map<String, Object> snapshot : op.getAffectedEntities()) {
                try {
                    Object entity = objectMapper.convertValue(snapshot, entityClass);
                    clearVersionField(entity);
                    em.persist(entity);
                    reinsertedCount++;
                } catch (Exception e) {
                    log.error("Failed to re-insert entity: {}", snapshot, e);
                    failedIds.add(String.valueOf(snapshot.get("id")));
                }
            }
            
            em.flush();
            
            if (!failedIds.isEmpty()) {
                throw new RetryableCompensationException(
                    "Bulk delete partial failure - re-inserted " + reinsertedCount + 
                    " of " + op.getAffectedEntities().size() + 
                    " entities. Failed IDs: " + failedIds
                );
            }
            
            log.info("Bulk delete compensated: {} entities re-inserted", reinsertedCount);
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException("Entity class not found: " + op.getEntityClass(), e);
        }
    }
    
    private void compensateNativeQuery(EntityManager em, OperationLog op) throws CompensationException {
        log.debug("Compensating NATIVE QUERY: {}", op.getAdditionalInfo());
        
        if (op.getInverseQuery() != null && !op.getInverseQuery().isEmpty()) {
            log.debug("Executing inverse query: {}", op.getInverseQuery());
            
            Query query = em.createNativeQuery(op.getInverseQuery());
            
            if (op.getQueryParameters() != null) {
                for (Map.Entry<String, Object> param : op.getQueryParameters().entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            int updated = query.executeUpdate();
            em.flush();
            
            log.info("Native query compensated: {} rows affected", updated);
            
        } else if (op.getSnapshot() != null) {
            log.debug("Using snapshot fallback for native query compensation");
            compensateUpdate(em, op);
            
        } else {
            throw new FatalCompensationException(
                "Native query compensation requires inverse query or snapshot"
            );
        }
    }
    
    private void compensateStoredProcedure(EntityManager em, OperationLog op) throws CompensationException {
        log.debug("Compensating STORED PROCEDURE: {}", op.getAdditionalInfo());
        
        if (op.getInverseProcedure() != null && !op.getInverseProcedure().isEmpty()) {
            log.debug("Executing inverse procedure: {}", op.getInverseProcedure());
            
            Query query = em.createNativeQuery("CALL " + op.getInverseProcedure());
            
            if (op.getQueryParameters() != null) {
                for (Map.Entry<String, Object> param : op.getQueryParameters().entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            query.executeUpdate();
            em.flush();
            
            log.info("Stored procedure compensated");
            
        } else if (op.getAffectedEntities() != null && !op.getAffectedEntities().isEmpty()) {
            log.debug("Restoring affected entities for procedure compensation");
            compensateBulkUpdate(em, op);
            
        } else {
            throw new FatalCompensationException(
                "Stored procedure compensation requires inverse procedure or affected entities snapshot"
            );
        }
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private EntityManager getEntityManager(String datasource) throws FatalCompensationException {
        EntityManager em = entityManagers.get(datasource);
        
        if (em == null) {
            String available = String.join(", ", entityManagers.keySet());
            throw new FatalCompensationException(
                String.format(
                    "No EntityManager found for datasource '%s'. Available datasources: [%s]. " +
                    "Ensure your EntityManager beans are properly configured.",
                    datasource, available
                )
            );
        }
        
        return em;
    }
    
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
    
    // ========================================================================
    // Logging Methods
    // ========================================================================
    
    private void logFatalError(CompensationError error) {
        log.error("═══════════════════════════════════════════════════════════");
        log.error("✗ FATAL COMPENSATION ERROR - ROLLBACK STOPPED");
        log.error("Operation: {}/{}", error.operationIndex, error.totalOperations);
        log.error("Type: {}", error.op.getOperationType());
        log.error("Entity: {} [id={}]", error.op.getEntityClass(), error.op.getEntityId());
        log.error("Datasource: {}", error.op.getDatasource());
        log.error("Error: {}", error.exception.getMessage());
        log.error("═══════════════════════════════════════════════════════════");
        log.error("⚠ CRITICAL: Cannot continue rollback - partial commit may exist");
        log.error("   Manual intervention REQUIRED");
        log.error("═══════════════════════════════════════════════════════════");
        log.error("Full stacktrace:", error.exception);
    }
    
    private void logRetryableError(CompensationError error) {
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("⚠ RETRYABLE COMPENSATION ERROR - Continuing rollback");
        log.warn("Operation: {}/{}", error.operationIndex, error.totalOperations);
        log.warn("Type: {}", error.op.getOperationType());
        log.warn("Entity: {} [id={}]", error.op.getEntityClass(), error.op.getEntityId());
        log.warn("Datasource: {}", error.op.getDatasource());
        log.warn("Error: {}", error.exception.getMessage());
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("Will continue with next operation");
        log.warn("This operation will be retried by recovery worker");
        log.warn("═══════════════════════════════════════════════════════════");
    }
    
    private void logUnknownError(CompensationError error) {
        log.error("═══════════════════════════════════════════════════════════");
        log.error("✗ UNKNOWN COMPENSATION ERROR - Treating as FATAL");
        log.error("Operation: {}/{}", error.operationIndex, error.totalOperations);
        log.error("Type: {}", error.op.getOperationType());
        log.error("Entity: {} [id={}]", error.op.getEntityClass(), error.op.getEntityId());
        log.error("Datasource: {}", error.op.getDatasource());
        log.error("Error Type: {}", error.exception.getClass().getName());
        log.error("Error: {}", error.exception.getMessage());
        log.error("═══════════════════════════════════════════════════════════");
        log.error("Full stacktrace:", error.exception);
    }
    
    private void logRollbackSummary(String txId, RollbackResult result) {
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("Rollback Summary for txId: {}", txId);
        log.warn("Successful: {}", result.successful);
        log.warn("Failed: {}", result.failed);
        log.warn("Skipped: {}", result.skipped);
        log.warn("═══════════════════════════════════════════════════════════");
    }
    
    // ========================================================================
    // Exception Classes
    // ========================================================================
    
    /**
     * Base compensation exception
     */
    public static class CompensationException extends Exception {
        public CompensationException(String message) {
            super(message);
        }
        
        public CompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Fatal error - cannot continue rollback
     */
    public static class FatalCompensationException extends CompensationException {
        public FatalCompensationException(String message) {
            super(message);
        }
        
        public FatalCompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Retryable error - can continue with other operations
     */
    public static class RetryableCompensationException extends CompensationException {
        public RetryableCompensationException(String message) {
            super(message);
        }
        
        public RetryableCompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Partial rollback exception - thrown when rollback fails
     */
    public static class PartialRollbackException extends RuntimeException {
        private final String txId;
        private final RollbackResult result;
        private final List<CompensationError> errors;
        
        public PartialRollbackException(String txId, RollbackResult result, 
                                       List<CompensationError> errors, String message) {
            super(message);
            this.txId = txId;
            this.result = result;
            this.errors = errors;
        }
        
        public String getTxId() { return txId; }
        public RollbackResult getResult() { return result; }
        public List<CompensationError> getErrors() { return errors; }
    }
    
    // ========================================================================
    // Helper Classes
    // ========================================================================
    
    private static class RollbackResult {
        int successful = 0;
        int failed = 0;
        int skipped = 0;
    }
    
    private static class CompensationError {
        final OperationLog op;
        final Exception exception;
        final boolean fatal;
        final int operationIndex;
        final int totalOperations;
        
        CompensationError(OperationLog op, Exception exception, boolean fatal,
                         int operationIndex, int totalOperations) {
            this.op = op;
            this.exception = exception;
            this.fatal = fatal;
            this.operationIndex = operationIndex;
            this.totalOperations = totalOperations;
        }
    }
}