package id.go.kemenkeu.djpbn.sakti.tx.core.compensate;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Field;
import java.util.*;

/**
 * ENHANCED: Smart compensation with version checking, business keys, and soft delete
 * 
 * @version 2.0.0
 */
public class CompensatingTransactionExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(CompensatingTransactionExecutor.class);
    
    private final Map<String, EntityManagerFactory> entityManagerFactories;
    private final ObjectMapper objectMapper;
    private final CompensationCircuitBreaker circuitBreaker;
    private final boolean strictVersionCheck;
    
    public CompensatingTransactionExecutor(
            Map<String, EntityManagerFactory> entityManagerFactories,
            ObjectMapper objectMapper,
            CompensationCircuitBreaker circuitBreaker,
            boolean strictVersionCheck) {
        this.entityManagerFactories = entityManagerFactories;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.strictVersionCheck = strictVersionCheck;
        
        log.info("CompensatingTransactionExecutor initialized (v2.1 - thread-safe)");
    }
    
    /**
     * SAFE: Rollback with proper EntityManager lifecycle
     */
    public void rollback(TransactionLog txLog) {
        String txId = txLog.getTxId();
        MDC.put("txId", txId);
        
        // Check circuit breaker
        if (!circuitBreaker.allowCompensation(txId)) {
            log.error("Circuit breaker OPEN - compensation blocked for txId: {}", txId);
            throw new CircuitBreakerOpenException(
                "Circuit breaker open - too many compensation failures for " + txId);
        }
        
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("Starting SMART COMPENSATING ROLLBACK");
        log.warn("Transaction ID: {}", txId);
        log.warn("Business Key: {}", txLog.getBusinessKey());
        log.warn("Total Operations: {}", txLog.getOperations().size());
        log.warn("═══════════════════════════════════════════════════════════");
        
        // SAFE: Create NEW EntityManagers for compensation
        Map<String, EntityManager> entityManagers = createCompensationEntityManagers();
        
        // SAFE: Start local transactions for compensation
        Map<String, jakarta.persistence.EntityTransaction> transactions = new HashMap<>();
        for (Map.Entry<String, EntityManager> entry : entityManagers.entrySet()) {
            try {
                EntityManager em = entry.getValue();
                jakarta.persistence.EntityTransaction tx = em.getTransaction();
                tx.begin();
                transactions.put(entry.getKey(), tx);
                log.debug("Started compensation transaction for: {}", entry.getKey());
            } catch (Exception e) {
                log.error("Failed to start compensation transaction: {}", entry.getKey(), e);
            }
        }
        
        try {
            List<OperationLog> operations = txLog.getOperationsInReverseOrder();
            RollbackResult result = new RollbackResult();
            List<CompensationError> criticalErrors = new ArrayList<>();
            
            for (int i = 0; i < operations.size(); i++) {
                OperationLog op = operations.get(i);
                
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
                    
                    // Use EntityManager from compensation map
                    compensateOperationSafe(op, entityManagers);
                    
                    op.setCompensated(true);
                    result.successful++;
                    
                    log.info("✓ Compensation successful: {} {} [id={}]", 
                        op.getOperationType(), op.getEntityClass(), op.getEntityId());
                    
                } catch (FatalCompensationException fatal) {
                    op.setCompensationError(fatal.getMessage());
                    result.failed++;
                    
                    CompensationError error = new CompensationError(
                        op, fatal, true, i + 1, operations.size()
                    );
                    criticalErrors.add(error);
                    
                    logFatalError(error);
                    circuitBreaker.recordFailure(txId);
                    break;
                    
                } catch (RetryableCompensationException retryable) {
                    op.setCompensationError(retryable.getMessage());
                    result.failed++;
                    
                    CompensationError error = new CompensationError(
                        op, retryable, false, i + 1, operations.size()
                    );
                    criticalErrors.add(error);
                    
                    logRetryableError(error);
                    
                } catch (Exception unknown) {
                    op.setCompensationError(unknown.getMessage());
                    result.failed++;
                    
                    CompensationError error = new CompensationError(
                        op, unknown, true, i + 1, operations.size()
                    );
                    criticalErrors.add(error);
                    
                    logUnknownError(error);
                    circuitBreaker.recordFailure(txId);
                    break;
                    
                } finally {
                    MDC.remove("opSequence");
                    MDC.remove("opType");
                }
            }
            
            // COMMIT compensation transactions
            for (Map.Entry<String, jakarta.persistence.EntityTransaction> entry : transactions.entrySet()) {
                try {
                    if (entry.getValue().isActive()) {
                        entry.getValue().commit();
                        log.debug("Committed compensation transaction: {}", entry.getKey());
                    }
                } catch (Exception e) {
                    log.error("Failed to commit compensation transaction: {}", 
                        entry.getKey(), e);
                }
            }
            
            logRollbackSummary(txId, result);
            
            if (!criticalErrors.isEmpty()) {
                throw new PartialRollbackException(
                    txId, result, criticalErrors,
                    "Rollback partially failed: " + result.failed + "/" + operations.size()
                );
            }
            
            circuitBreaker.recordSuccess(txId);
            log.info("✓ Rollback completed successfully for txId: {}", txId);
            
        } catch (Exception e) {
            // ROLLBACK compensation transactions on error
            for (Map.Entry<String, jakarta.persistence.EntityTransaction> entry : transactions.entrySet()) {
                try {
                    if (entry.getValue().isActive()) {
                        entry.getValue().rollback();
                        log.debug("Rolled back compensation transaction: {}", entry.getKey());
                    }
                } catch (Exception rollbackError) {
                    log.error("Failed to rollback compensation transaction: {}", 
                        entry.getKey(), rollbackError);
                }
            }
            throw e;
            
        } finally {
            // CLOSE all EntityManagers
            for (Map.Entry<String, EntityManager> entry : entityManagers.entrySet()) {
                try {
                    if (entry.getValue().isOpen()) {
                        entry.getValue().close();
                        log.trace("Closed compensation EntityManager: {}", entry.getKey());
                    }
                } catch (Exception e) {
                    log.error("Failed to close compensation EntityManager: {}", 
                        entry.getKey(), e);
                }
            }
            
            MDC.remove("txId");
        }
    }
    
    /**
     * SAFE: Create EntityManagers for compensation
     */
    private Map<String, EntityManager> createCompensationEntityManagers() {
        Map<String, EntityManager> entityManagers = new HashMap<>();
        
        for (Map.Entry<String, EntityManagerFactory> entry : entityManagerFactories.entrySet()) {
            String datasource = entry.getKey();
            EntityManagerFactory emf = entry.getValue();
            
            // Skip duplicate entries (aliases)
            if (datasource.endsWith("TransactionManager") && 
                entityManagerFactories.containsKey(datasource.replace("TransactionManager", ""))) {
                continue;
            }
            
            try {
                EntityManager em = emf.createEntityManager();
                entityManagers.put(datasource, em);
                log.trace("Created compensation EntityManager for: {}", datasource);
            } catch (Exception e) {
                log.error("Failed to create compensation EntityManager: {}", 
                    datasource, e);
            }
        }
        
        return entityManagers;
    }
    
    /**
     * Modified to accept entityManagers map
     */
    private void compensateOperationSafe(OperationLog op, 
                                         Map<String, EntityManager> entityManagers) 
            throws CompensationException {
        
        EntityManager em = getEntityManager(op.getDatasource(), entityManagers);
        
        try {
            switch (op.getOperationType()) {
                case INSERT:
                    compensateInsertSmart(em, op);
                    break;
                case UPDATE:
                    compensateUpdateSmart(em, op);
                    break;
                case DELETE:
                    compensateDeleteSmart(em, op);
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
            throw e;
        } catch (jakarta.persistence.OptimisticLockException e) {
            throw new RetryableCompensationException(
                "Optimistic lock failure - concurrent modification", e
            );
        } catch (jakarta.persistence.EntityNotFoundException e) {
            throw new RetryableCompensationException(
                "Entity not found - may have been deleted", e
            );
        } catch (jakarta.persistence.PersistenceException e) {
            if (e.getMessage() != null && 
                (e.getMessage().contains("constraint") || 
                 e.getMessage().contains("foreign key"))) {
                throw new FatalCompensationException(
                    "Database constraint violation", e
                );
            }
            throw new RetryableCompensationException(
                "Database operation failed", e
            );
        } catch (Exception e) {
            throw new FatalCompensationException(
                "Unexpected error during compensation", e
            );
        }
    }
    
    /**
     * ENHANCED: Smart INSERT compensation
     * - Check if entity still exists
     * - Try soft delete first
     * - Fallback to hard delete
     */
    private void compensateInsertSmart(EntityManager em, OperationLog op) 
            throws CompensationException {
        
        try {
            log.debug("DELETE inserted record: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
            Class<?> entityClass = Class.forName(op.getEntityClass());
            Object entity = em.find(entityClass, op.getEntityId());
            
            if (entity == null) {
                log.warn("Entity already deleted: {} [id={}] - idempotent", 
                    op.getEntityClass(), op.getEntityId());
                return;
            }
            
            // Check version if exists
            Object currentVersion = extractVersion(entity);
            if (currentVersion != null && op.getSnapshot() != null) {
                Object snapshotVersion = extractVersionFromSnapshot(op.getSnapshot());
                
                if (snapshotVersion != null && !currentVersion.equals(snapshotVersion)) {
                    log.warn("Entity {} modified after insert (version {} → {})",
                        op.getEntityId(), snapshotVersion, currentVersion);
                    
                    if (strictVersionCheck) {
                        throw new RetryableCompensationException(
                            "Entity modified concurrently - version mismatch"
                        );
                    }
                }
            }
            
            // Try soft delete first
            if (hasSoftDeleteColumn(entityClass)) {
                softDelete(em, entity);
                log.info("Soft-deleted: {} [id={}]", op.getEntityClass(), op.getEntityId());
            } else {
                // Hard delete
                if (!em.contains(entity)) {
                    entity = em.merge(entity);
                }
                em.remove(entity);
                log.info("Hard-deleted: {} [id={}]", op.getEntityClass(), op.getEntityId());
            }
            
            em.flush();
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException(
                "Entity class not found: " + op.getEntityClass(), e
            );
        } catch (Exception e) {
            log.error("compensateInsertSmart: ", e);
            throw new FatalCompensationException(
                "Another Exception: ", e
            );
        }
    }
    
    /**
     * ENHANCED: Smart UPDATE compensation
     * - Find by PK or business key
     * - Check version
     * - Restore from snapshot
     */
    private void compensateUpdateSmart(EntityManager em, OperationLog op) 
            throws CompensationException {
        
        try {
            log.debug("RESTORE updated record: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
            if (op.getSnapshot() == null) {
                throw new FatalCompensationException(
                    "No snapshot for UPDATE rollback: " + op.getEntityClass()
                );
            }
            
            Class<?> entityClass = Class.forName(op.getEntityClass());
            
            // Try find by primary key
            Object entity = em.find(entityClass, op.getEntityId());
            
            // Fallback: Find by business key
            if (entity == null && op.getQueryParameters() != null) {
                entity = findByBusinessKey(em, entityClass, op.getQueryParameters());
                
                if (entity != null) {
                    log.warn("Entity {} not found by PK {}, found by business key",
                        op.getEntityClass(), op.getEntityId());
                    
                    // Flag risk
                    // (Risk flags would be recorded in context if available)
                }
            }
            
            if (entity == null) {
                throw new FatalCompensationException(
                    "Entity not found by PK or business key: " + op.getEntityId()
                );
            }
            
            // Check version
            Object currentVersion = extractVersion(entity);
            if (currentVersion != null) {
                Object snapshotVersion = extractVersionFromSnapshot(op.getSnapshot());
                
                if (snapshotVersion != null && !currentVersion.equals(snapshotVersion)) {
                    log.warn("Entity {} modified after update (version {} → {})",
                        op.getEntityId(), snapshotVersion, currentVersion);
                    
                    if (strictVersionCheck) {
                        throw new RetryableCompensationException(
                            "Entity modified concurrently - version mismatch"
                        );
                    }
                }
            }
            
            // Restore from snapshot
            Object snapshotEntity = objectMapper.convertValue(op.getSnapshot(), entityClass);
            clearVersionField(snapshotEntity);
            
            Object merged = em.merge(snapshotEntity);
            em.flush();
            
            log.info("Restored: {} [id={}]", op.getEntityClass(), op.getEntityId());
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException(
                "Entity class not found: " + op.getEntityClass(), e
            );
        }
    }
    
    /**
     * ENHANCED: Smart DELETE compensation
     * - Check if entity already exists
     * - Re-insert from snapshot
     */
    private void compensateDeleteSmart(EntityManager em, OperationLog op) 
            throws CompensationException {
        
        try {
            log.debug("RE-INSERT deleted record: {} [id={}]", 
                op.getEntityClass(), op.getEntityId());
            
            if (op.getSnapshot() == null) {
                throw new FatalCompensationException(
                    "No snapshot for DELETE rollback: " + op.getEntityClass()
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
            throw new FatalCompensationException(
                "Entity class not found: " + op.getEntityClass(), e
            );
        }
    }
    
    // ========================================================================
    // Existing methods (BULK, NATIVE, PROCEDURE) - unchanged from original
    // ========================================================================
    
    private void compensateBulkUpdate(EntityManager em, OperationLog op) 
            throws CompensationException {
        // Original implementation...
        log.debug("Compensating BULK UPDATE: {}", op.getAdditionalInfo());
        
        if (op.getAffectedEntities() == null || op.getAffectedEntities().isEmpty()) {
            throw new FatalCompensationException(
                "Bulk update requires affected entities snapshot"
            );
        }
        
        try {
            Class<?> entityClass = Class.forName(op.getEntityClass());
            int restoredCount = 0;
            
            for (Map<String, Object> snapshot : op.getAffectedEntities()) {
                Object entity = objectMapper.convertValue(snapshot, entityClass);
                clearVersionField(entity);
                em.merge(entity);
                restoredCount++;
            }
            
            em.flush();
            log.info("Bulk update compensated: {} entities restored", restoredCount);
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException(
                "Entity class not found: " + op.getEntityClass(), e
            );
        }
    }
    
    private void compensateBulkDelete(EntityManager em, OperationLog op) 
            throws CompensationException {
        // Original implementation...
        log.debug("Compensating BULK DELETE: {}", op.getAdditionalInfo());
        
        if (op.getAffectedEntities() == null || op.getAffectedEntities().isEmpty()) {
            throw new FatalCompensationException(
                "Bulk delete requires affected entities snapshot"
            );
        }
        
        try {
            Class<?> entityClass = Class.forName(op.getEntityClass());
            int reinsertedCount = 0;
            
            for (Map<String, Object> snapshot : op.getAffectedEntities()) {
                Object entity = objectMapper.convertValue(snapshot, entityClass);
                clearVersionField(entity);
                em.persist(entity);
                reinsertedCount++;
            }
            
            em.flush();
            log.info("Bulk delete compensated: {} entities re-inserted", reinsertedCount);
            
        } catch (ClassNotFoundException e) {
            throw new FatalCompensationException(
                "Entity class not found: " + op.getEntityClass(), e
            );
        }
    }
    
    private void compensateNativeQuery(EntityManager em, OperationLog op) 
            throws CompensationException {
        // Original implementation...
        log.debug("Compensating NATIVE QUERY: {}", op.getAdditionalInfo());
        
        if (op.getInverseQuery() != null && !op.getInverseQuery().isEmpty()) {
            Query query = em.createNativeQuery(op.getInverseQuery());
            
            if (op.getQueryParameters() != null) {
                for (Map.Entry<String, Object> param : op.getQueryParameters().entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            int updated = query.executeUpdate();
            em.flush();
            log.info("Native query compensated: {} rows affected", updated);
        } else {
            throw new FatalCompensationException(
                "Native query requires inverse query or snapshot"
            );
        }
    }
    
    private void compensateStoredProcedure(EntityManager em, OperationLog op) 
            throws CompensationException {
        // Original implementation...
        log.debug("Compensating STORED PROCEDURE: {}", op.getAdditionalInfo());
        
        if (op.getInverseProcedure() != null && !op.getInverseProcedure().isEmpty()) {
            Query query = em.createNativeQuery("CALL " + op.getInverseProcedure());
            
            if (op.getQueryParameters() != null) {
                for (Map.Entry<String, Object> param : op.getQueryParameters().entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            query.executeUpdate();
            em.flush();
            log.info("Stored procedure compensated");
        } else {
            throw new FatalCompensationException(
                "Stored procedure requires inverse procedure"
            );
        }
    }
    
    /**
     * SAFE: Get EntityManager from compensation map
     */
    private EntityManager getEntityManager(String datasource, 
                                           Map<String, EntityManager> entityManagers) 
            throws FatalCompensationException {
        
        EntityManager em = entityManagers.get(datasource);
        
        if (em == null) {
            // Try with/without "TransactionManager" suffix
            if (datasource.endsWith("TransactionManager")) {
                String shortName = datasource.replace("TransactionManager", "");
                em = entityManagers.get(shortName);
            } else {
                String longName = datasource + "TransactionManager";
                em = entityManagers.get(longName);
            }
            
            if (em == null) {
                String available = String.join(", ", entityManagers.keySet());
                throw new FatalCompensationException(
                    String.format(
                        "No EntityManager found for datasource '%s'. Available: [%s]",
                        datasource, available
                    )
                );
            }
        }
        
        return em;
    }

    private Object extractVersion(Object entity) {
        try {
            for (Field field : entity.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Version.class)) {
                    field.setAccessible(true);
                    return field.get(entity);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract version: {}", e.getMessage());
        }
        return null;
    }
    
    private Object extractVersionFromSnapshot(Object snapshot) {
        if (snapshot instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) snapshot;
            // Try common version field names
            for (String versionField : Arrays.asList("version", "Version", "_version")) {
                if (map.containsKey(versionField)) {
                    return map.get(versionField);
                }
            }
        }
        return null;
    }
    
    private void clearVersionField(Object entity) {
        try {
            for (Field field : entity.getClass().getDeclaredFields()) {
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
    
    private boolean hasSoftDeleteColumn(Class<?> entityClass) {
        try {
            Field deletedField = entityClass.getDeclaredField("deleted");
            return deletedField.getType() == Boolean.class || 
                   deletedField.getType() == boolean.class;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
    
    private void softDelete(EntityManager em, Object entity) throws Exception {
        Field deletedField = entity.getClass().getDeclaredField("deleted");
        deletedField.setAccessible(true);
        deletedField.set(entity, true);
        em.merge(entity);
    }
    
    private Object findByBusinessKey(EntityManager em, Class<?> entityClass,
                                     Map<String, Object> businessKeys) {
        if (businessKeys == null || businessKeys.isEmpty()) {
            return null;
        }
        
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<?> query = cb.createQuery(entityClass);
        Root<?> root = query.from(entityClass);
        
        List<Predicate> predicates = new ArrayList<>();
        for (Map.Entry<String, Object> entry : businessKeys.entrySet()) {
            try {
                predicates.add(cb.equal(root.get(entry.getKey()), entry.getValue()));
            } catch (IllegalArgumentException e) {
                log.debug("Field {} not found in entity, skipping", entry.getKey());
            }
        }
        
        if (predicates.isEmpty()) {
            return null;
        }
        
        query.where(predicates.toArray(new Predicate[0]));
        
        List<?> results = em.createQuery(query).getResultList();
        return results.isEmpty() ? null : results.get(0);
    }
    
    // ========================================================================
    // Logging methods (unchanged)
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
        log.error("Full stacktrace:", error.exception);
    }
    
    private void logRetryableError(CompensationError error) {
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("⚠ RETRYABLE COMPENSATION ERROR - Continuing rollback");
        log.warn("Operation: {}/{}", error.operationIndex, error.totalOperations);
        log.warn("Type: {}", error.op.getOperationType());
        log.warn("Entity: {} [id={}]", error.op.getEntityClass(), error.op.getEntityId());
        log.warn("Error: {}", error.exception.getMessage());
        log.warn("═══════════════════════════════════════════════════════════");
    }
    
    private void logUnknownError(CompensationError error) {
        log.error("═══════════════════════════════════════════════════════════");
        log.error("✗ UNKNOWN COMPENSATION ERROR - Treating as FATAL");
        log.error("Operation: {}/{}", error.operationIndex, error.totalOperations);
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
    // Exception classes (unchanged)
    // ========================================================================
    
    public static class CompensationException extends Exception {
        public CompensationException(String message) {
            super(message);
        }
        public CompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class FatalCompensationException extends CompensationException {
        public FatalCompensationException(String message) {
            super(message);
        }
        public FatalCompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class RetryableCompensationException extends CompensationException {
        public RetryableCompensationException(String message) {
            super(message);
        }
        public RetryableCompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
    
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