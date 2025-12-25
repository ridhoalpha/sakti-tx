package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.*;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.TransactionRollbackException;
import id.go.kemenkeu.djpbn.sakti.tx.core.listener.EntityOperationListener;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.mapper.EntityManagerDatasourceMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.metrics.TransactionMetrics;
import id.go.kemenkeu.djpbn.sakti.tx.core.validation.PreCommitValidator;
import id.go.kemenkeu.djpbn.sakti.tx.core.validation.ValidationIssue;
import id.go.kemenkeu.djpbn.sakti.tx.core.validation.ValidationResult;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PRODUCTION-GRADE DISTRIBUTED TRANSACTION ASPECT
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * CRITICAL FIXES IMPLEMENTED:
 * 
 * 1. CONTEXT LIFECYCLE FIX:
 *    - Context cleanup HANYA setelah compensation selesai
 *    - Separate tracking: compensationExecuted flag
 *    - Guaranteed cleanup order
 * 
 * 2. NESTED TRANSACTION PROTECTION:
 *    - Detect nested @SaktiDistributedTx
 *    - Prevent context pollution
 *    - Safe context inheritance
 * 
 * 3. TRANSACTION STATE CONSISTENCY:
 *    - Track commit point dengan AtomicBoolean
 *    - No rollback after successful commit
 *    - Clear error messages
 * 
 * 4. THREAD-SAFE RESOURCE MANAGEMENT:
 *    - All resources tracked in single structure
 *    - Guaranteed cleanup dengan multiple fallbacks
 *    - MDC cleanup di setiap exit path
 * 
 * 5. OBSERVABILITY:
 *    - Detailed logging di setiap phase
 *    - Context state tracking
 *    - Error categorization
 * 
 * @version 3.0.0-PRODUCTION
 * @author SAKTI Team
 */
@Aspect
@Component
@Order(0)
public class SaktiDistributedTxAspect {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiDistributedTxAspect.class);
    
    private final SaktiTxProperties properties;
    private final TransactionLogManager logManager;
    private final CompensatingTransactionExecutor compensator;
    private final EntityManagerDatasourceMapper emMapper;
    private final LockManager lockManager;
    private final PreCommitValidator preCommitValidator;
    private final TransactionMetrics metrics;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    private final Map<String, PlatformTransactionManager> transactionManagers;
    private final Map<String, EntityManager> allEntityManagers;
    
    public SaktiDistributedTxAspect(
            SaktiTxProperties properties,
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            EntityManagerDatasourceMapper emMapper,
            @Autowired(required = false) LockManager lockManager,
            PreCommitValidator preCommitValidator,
            TransactionMetrics metrics,
            @Autowired(required = false) Map<String, PlatformTransactionManager> transactionManagers) {
        
        this.properties = properties;
        this.logManager = logManager;
        this.compensator = compensator;
        this.emMapper = emMapper;
        this.lockManager = lockManager;
        this.preCommitValidator = preCommitValidator;
        this.metrics = metrics;
        this.allEntityManagers = emMapper.createEntityManagersForCompensation();
        this.transactionManagers = transactionManagers != null ? transactionManagers : new HashMap<>();
        
        log.info("SaktiDistributedTxAspect v3.0-PRODUCTION initialized");
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        
        if (!properties.getMultiDb().isEnabled()) {
            return pjp.proceed();
        }
        
        // ═══════════════════════════════════════════════════════════════
        // NESTED TRANSACTION DETECTION
        // ═══════════════════════════════════════════════════════════════
        
        boolean isNestedCall = SaktiTxContextHolder.hasContext();
        
        if (isNestedCall) {
            log.debug("Nested @SaktiDistributedTx detected - delegating to parent transaction");
            return pjp.proceed();
        }
        
        Instant startTime = Instant.now();
        metrics.recordTransactionStart();
        
        // ═══════════════════════════════════════════════════════════════
        // RESOURCE TRACKING STRUCTURE
        // ═══════════════════════════════════════════════════════════════
        
        TransactionResources resources = new TransactionResources();
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 1: INITIALIZATION
            // ═══════════════════════════════════════════════════════════════
            
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            SaktiDistributedTx annotation = method.getAnnotation(SaktiDistributedTx.class);
            
            String businessKey = generateBusinessKey(pjp);
            
            SaktiTxContext context = new SaktiTxContext.Builder()
                .businessKey(businessKey)
                .phase(TransactionPhase.CREATED)
                .build();
            
            resources.txId = context.getTxId();
            resources.context = context;
            
            SaktiTxContextHolder.set(context);
            
            resources.txLog = logManager.createLog(businessKey);
            
            MDC.put("txId", resources.txId);
            MDC.put("businessKey", businessKey);
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction STARTED (v3.0-PRODUCTION)");
            log.info("Transaction ID: {}", resources.txId);
            log.info("Business Key: {}", businessKey);
            log.info("Method: {}.{}", 
                signature.getDeclaringType().getSimpleName(), 
                signature.getName());
            log.info("═══════════════════════════════════════════════════════════");
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 2: ENTITY TRACKING SETUP
            // ═══════════════════════════════════════════════════════════════
            
            EntityOperationListener.EntityOperationContext existingCtx = 
                EntityOperationListener.getOperationContext();
            
            if (existingCtx != null && !existingCtx.isCleared()) {
                log.error("CRITICAL: Operation context leak detected!");
                log.error("   Thread: {}", Thread.currentThread().getId());
                log.error("   Forcing cleanup of leaked context...");
                EntityOperationListener.clearOperationContext();
            }
            
            resources.operationContext = new EntityOperationListener.EntityOperationContext(true);
            EntityOperationListener.setOperationContext(resources.operationContext);
            
            context = context.withPhase(TransactionPhase.COLLECTING);
            SaktiTxContextHolder.update(context);
            resources.context = context;
            
            log.debug("Entity tracking context initialized");
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 3: START TRANSACTIONS
            // ═══════════════════════════════════════════════════════════════
            
            startAllTransactions(resources.activeTransactions);
            
            for (String datasource : resources.activeTransactions.keySet()) {
                ResourceEnlistment resource = new ResourceEnlistment(datasource, "DATABASE");
                context = context.withResource(resource);
            }
            SaktiTxContextHolder.update(context);
            resources.context = context;
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 4: ACQUIRE LOCK
            // ═══════════════════════════════════════════════════════════════
            
            if (!annotation.lockKey().isEmpty() && lockManager != null) {
                String lockKey = evaluateExpression(annotation.lockKey(), pjp);
                resources.lock = acquireLock(lockKey);
                context = context.withLock(lockKey);
                SaktiTxContextHolder.update(context);
                resources.context = context;
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 5: EXECUTE BUSINESS LOGIC
            // ═══════════════════════════════════════════════════════════════
            
            log.debug("Executing business logic...");
            Object result = pjp.proceed();
            log.debug("Business logic completed successfully");
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 6: FLUSH & COLLECT OPERATIONS
            // ═══════════════════════════════════════════════════════════════
            
            flushAllEntityManagers();
            
            List<EntityOperationListener.ConfirmedOperation> operations = 
                resources.operationContext.getConfirmedOperations();
            
            log.debug("Collected {} operations from tracking context", operations.size());
            
            for (EntityOperationListener.ConfirmedOperation op : operations) {
                EntityManager em = findEntityManagerForEntity(op.entityClass);
                String datasource = emMapper.getDatasourceName(em);
                
                resources.txLog.recordOperation(
                    datasource,
                    op.type,
                    op.entityClass,
                    op.entityId,
                    op.snapshot
                );
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 7: PRE-COMMIT VALIDATION
            // ═══════════════════════════════════════════════════════════════
            
            context = context.withPhase(TransactionPhase.VALIDATING);
            SaktiTxContextHolder.update(context);
            resources.context = context;
            
            log.info("Starting pre-commit validation...");
            ValidationResult validation = preCommitValidator.validate(context);
            
            if (!validation.canProceed()) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("PRE-COMMIT VALIDATION FAILED");
                log.error("═══════════════════════════════════════════════════════════");
                
                for (ValidationIssue issue : validation.getErrors()) {
                    log.error("  - [ERROR] {}: {}", issue.getCode(), issue.getMessage());
                }
                
                log.error("Transaction ABORTED before commit - databases remain consistent");
                log.error("═══════════════════════════════════════════════════════════");
                
                rollbackAllTransactions(resources.activeTransactions);
                
                Duration duration = Duration.between(startTime, Instant.now());
                metrics.recordTransactionRolledBack(duration);
                
                throw new ValidationException("Pre-commit validation failed", validation);
            }
            
            for (ValidationIssue issue : validation.getWarnings()) {
                log.warn("  - [WARNING] {}: {}", issue.getCode(), issue.getMessage());
            }
            
            log.info("Validation passed - Risk Level: {}", validation.getOverallRisk());
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 8: PREPARED STATE
            // ═══════════════════════════════════════════════════════════════
            
            context = context.withPhase(TransactionPhase.PREPARED);
            SaktiTxContextHolder.update(context);
            resources.context = context;
            
            for (ResourceEnlistment resource : context.getResources()) {
                resource.setPrepared(true);
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 9: COMMIT (POINT OF NO RETURN)
            // ═══════════════════════════════════════════════════════════════
            
            context = context.withPhase(TransactionPhase.COMMITTING);
            SaktiTxContextHolder.update(context);
            resources.context = context;
            
            log.debug("Starting commit phase...");
            commitAllTransactions(resources.activeTransactions);
            resources.committed.set(true); // ✓ COMMITTED FLAG SET
            
            context = context.withPhase(TransactionPhase.COMMITTED);
            SaktiTxContextHolder.update(context);
            resources.context = context;
            
            commit(resources.txLog);
            
            Duration duration = Duration.between(startTime, Instant.now());
            metrics.recordTransactionCommitted(duration);
            
            for (Map.Entry<id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag, Integer> entry : 
                    context.getRiskMetrics().entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    metrics.recordRiskFlag(entry.getKey());
                }
            }
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction COMMITTED");
            log.info("Transaction ID: {}", resources.txId);
            log.info("Operations: {}", operations.size());
            log.info("Duration: {}ms", duration.toMillis());
            log.info("Risk Level: {}", context.getOverallRiskLevel());
            log.info("═══════════════════════════════════════════════════════════");
            
            return result;
            
        } catch (Throwable error) {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 10: ERROR HANDLING
            // ═══════════════════════════════════════════════════════════════
            
            String errorTxId = resources.txId != null ? resources.txId : "unknown";
            int operationCount = resources.txLog != null ? resources.txLog.getOperations().size() : 0;
            
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Distributed Transaction FAILED");
            log.error("Transaction ID: {}", errorTxId);
            log.error("Operations Recorded: {}", operationCount);
            log.error("Committed: {}", resources.committed.get());
            log.error("Error: {}", error.getMessage());
            log.error("═══════════════════════════════════════════════════════════");
            
            // ═══════════════════════════════════════════════════════════════
            // CRITICAL CHECK: Sudah commit atau belum?
            // ═══════════════════════════════════════════════════════════════
            
            if (resources.committed.get()) {
                // ❌ FATAL: Error SETELAH commit berhasil
                log.error("═══════════════════════════════════════════════════════════");
                log.error("CRITICAL: Error occurred AFTER successful commit!");
                log.error("This should NOT happen - indicates application-level error");
                log.error("Databases are COMMITTED and CONSISTENT");
                log.error("NO COMPENSATION NEEDED");
                log.error("═══════════════════════════════════════════════════════════");
                
                Duration duration = Duration.between(startTime, Instant.now());
                metrics.recordTransactionCommitted(duration);
                
                // Re-throw original error - let application handle
                throw error;
            }
            
            // ✓ Belum commit - safe untuk rollback
            if (resources.context != null) {
                SaktiTxContext ctx = resources.context.withPhase(TransactionPhase.ROLLING_BACK);
                SaktiTxContextHolder.update(ctx);
                resources.context = ctx;
            }
            
            rollbackAllTransactions(resources.activeTransactions);
            
            // ═══════════════════════════════════════════════════════════════
            // COMPENSATION - Context masih ada!
            // ═══════════════════════════════════════════════════════════════
            
            boolean rollbackSuccess = false;
            if (resources.txLog != null && resources.txLog.getOperations().size() > 0) {
                rollbackSuccess = rollbackWithRetry(resources.txLog, error);
                resources.compensationExecuted = true;
            }
            
            Duration duration = Duration.between(startTime, Instant.now());
            if (rollbackSuccess) {
                metrics.recordTransactionRolledBack(duration);
            } else {
                metrics.recordTransactionFailed(duration);
            }
            
            throw new TransactionRollbackException(
                String.format("Transaction %s failed and %s: %s",
                    errorTxId,
                    rollbackSuccess ? "rolled back successfully" : "ROLLBACK FAILED",
                    error.getMessage()),
                error,
                errorTxId,
                operationCount,
                !rollbackSuccess
            );
            
        } finally {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 11: GUARANTEED CLEANUP
            // Context cleanup HANYA setelah compensation selesai
            // ═══════════════════════════════════════════════════════════════
            
            safeguardedCleanup(resources);
        }
    }
    
    /**
     * ═══════════════════════════════════════════════════════════════════════
     * PRODUCTION-GRADE CLEANUP
     * ═══════════════════════════════════════════════════════════════════════
     * 
     * GUARANTEES:
     * 1. Context cleanup ONLY after compensation done
     * 2. Multiple fallback mechanisms
     * 3. Never throws exceptions
     * 4. Thread-safe
     * 5. Idempotent (safe to call multiple times)
     */
    private void safeguardedCleanup(TransactionResources resources) {
        long threadId = Thread.currentThread().getId();
        
        try {
            log.trace("╔═══════════════════════════════════════════════════════════╗");
            log.trace("║         SAFEGUARDED CLEANUP - Thread: {}              ║", threadId);
            log.trace("╠═══════════════════════════════════════════════════════════╣");
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 1: Release Lock (can be done early)
            // ═══════════════════════════════════════════════════════════════
            
            if (resources.lock != null) {
                try {
                    resources.lock.release();
                    log.trace("║ [1/5] ✓ Lock released                                    ║");
                } catch (Throwable e) {
                    log.error("║ [1/5] ✗ Lock release failed: {}                    ║", e.getMessage());
                }
            } else {
                log.trace("║ [1/5] - No lock to release                                ║");
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 2: Wait for compensation (if needed)
            // ═══════════════════════════════════════════════════════════════
            
            if (resources.compensationExecuted) {
                log.trace("║ [2/5] ✓ Compensation completed - safe to cleanup context ║");
            } else {
                log.trace("║ [2/5] - No compensation needed                           ║");
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 3: Clear Operation Context (ONLY if we own it)
            // ═══════════════════════════════════════════════════════════════
            
            if (resources.operationContext != null) {
                try {
                    EntityOperationListener.EntityOperationContext currentCtx = 
                        EntityOperationListener.getOperationContext();
                    
                    if (currentCtx == resources.operationContext) {
                        EntityOperationListener.clearOperationContext();
                        log.trace("║ [3/5] ✓ Operation context cleared                        ║");
                    } else {
                        log.trace("║ [3/5] - Operation context already cleared by others      ║");
                    }
                    
                    // Verify
                    if (EntityOperationListener.getOperationContext() != null) {
                        log.error("║ [3/5] ✗ Context STILL present after clear - forcing...   ║");
                        EntityOperationListener.clearOperationContext();
                    }
                    
                } catch (Throwable e) {
                    log.error("║ [3/5] ✗ Operation context cleanup failed: {}       ║", e.getMessage());
                }
            } else {
                log.trace("║ [3/5] - No operation context to clear                    ║");
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 4: Clear TX Context (ONLY if we own it)
            // ═══════════════════════════════════════════════════════════════
            
            if (resources.context != null) {
                try {
                    SaktiTxContext currentCtx = SaktiTxContextHolder.get();
                    
                    if (currentCtx == resources.context) {
                        SaktiTxContextHolder.clear();
                        log.trace("║ [4/5] ✓ TX context cleared                               ║");
                    } else {
                        log.trace("║ [4/5] - TX context already cleared by others             ║");
                    }
                    
                    // Verify
                    if (SaktiTxContextHolder.get() != null) {
                        log.error("║ [4/5] ✗ Context STILL present - forcing clear...         ║");
                        SaktiTxContextHolder.clear();
                    }
                    
                } catch (Throwable e) {
                    log.error("║ [4/5] ✗ TX context cleanup failed: {}              ║", e.getMessage());
                }
            } else {
                log.trace("║ [4/5] - No TX context to clear                           ║");
            }
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 5: Clear MDC
            // ═══════════════════════════════════════════════════════════════
            
            try {
                MDC.remove("txId");
                MDC.remove("businessKey");
                log.trace("║ [5/5] ✓ MDC cleared                                       ║");
            } catch (Throwable e) {
                log.error("║ [5/5] ✗ MDC cleanup failed: {}                      ║", e.getMessage());
            }
            
            log.trace("╚═══════════════════════════════════════════════════════════╝");
            
        } catch (Throwable fatal) {
            // NEVER throw from cleanup
            log.error("FATAL: Cleanup failed catastrophically", fatal);
        }
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // RESOURCE TRACKING CLASS
    // ════════════════════════════════════════════════════════════════════════
    
    /**
     * Centralized resource tracking untuk guaranteed cleanup
     */
    private static class TransactionResources {
        String txId;
        TransactionLog txLog;
        SaktiTxContext context;
        EntityOperationListener.EntityOperationContext operationContext;
        LockManager.LockHandle lock;
        Map<String, TransactionStatus> activeTransactions = new HashMap<>();
        
        // State tracking
        AtomicBoolean committed = new AtomicBoolean(false);
        boolean compensationExecuted = false;
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // HELPER METHODS (unchanged from original)
    // ════════════════════════════════════════════════════════════════════════
    
    private void startAllTransactions(Map<String, TransactionStatus> activeTransactions) {
        if (transactionManagers.isEmpty()) {
            log.warn("No PlatformTransactionManager beans found");
            return;
        }

        for (Map.Entry<String, PlatformTransactionManager> entry : transactionManagers.entrySet()) {
            String datasource = entry.getKey();
            PlatformTransactionManager txManager = entry.getValue();

            try {
                DefaultTransactionDefinition def = new DefaultTransactionDefinition();
                def.setName("SAKTI-TX-" + datasource);
                def.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRED);

                TransactionStatus status = txManager.getTransaction(def);
                activeTransactions.put(datasource, status);

                log.debug("Started transaction for: {}", datasource);

            } catch (Exception e) {
                log.error("Failed to start transaction for {}", datasource, e);
            }
        }
    }

    private void flushAllEntityManagers() {
        for (Map.Entry<String, EntityManager> entry : allEntityManagers.entrySet()) {
            try {
                EntityManager em = entry.getValue();
                if (em.isJoinedToTransaction()) {
                    em.flush();
                    log.trace("Flushed: {}", entry.getKey());
                }
            } catch (Exception e) {
                log.warn("Flush failed for {}", entry.getKey(), e);
            }
        }
    }

    private void commitAllTransactions(Map<String, TransactionStatus> activeTransactions) {
        for (Map.Entry<String, TransactionStatus> entry : activeTransactions.entrySet()) {
            String datasource = entry.getKey();
            TransactionStatus status = entry.getValue();

            try {
                if (!status.isCompleted()) {
                    PlatformTransactionManager txManager = transactionManagers.get(datasource);
                    if (txManager != null) {
                        txManager.commit(status);
                        log.debug("Committed: {}", datasource);
                    }
                }
            } catch (Exception e) {
                log.error("Commit failed for {}", datasource, e);
                throw e;
            }
        }
        activeTransactions.clear();
    }

    private void rollbackAllTransactions(Map<String, TransactionStatus> activeTransactions) {
        for (Map.Entry<String, TransactionStatus> entry : activeTransactions.entrySet()) {
            String datasource = entry.getKey();
            TransactionStatus status = entry.getValue();

            try {
                if (!status.isCompleted()) {
                    PlatformTransactionManager txManager = transactionManagers.get(datasource);
                    if (txManager != null) {
                        txManager.rollback(status);
                        log.debug("Rolled back: {}", datasource);
                    }
                }
            } catch (Exception e) {
                log.error("Rollback failed for {}", datasource, e);
            }
        }
        activeTransactions.clear();
    }

    private boolean rollbackWithRetry(TransactionLog txLog, Throwable originalError) {
        String txId = txLog.getTxId();
        int maxAttempts = properties.getMultiDb().getMaxRollbackRetries();
        long baseBackoffMs = properties.getMultiDb().getRollbackRetryBackoffMs();

        logManager.markRollingBack(txId, originalError.getMessage());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Rollback attempt {}/{}", attempt, maxAttempts);

                metrics.recordCompensationAttempt();
                compensator.rollback(txLog);
                logManager.markRolledBack(txId);

                metrics.recordCompensationSuccess();

                log.info("✓ ROLLBACK SUCCESSFUL on attempt {}", attempt);
                return true;

            } catch (Exception e) {
                log.error("Rollback attempt {} FAILED: {}", attempt, e.getMessage());

                metrics.recordCompensationFailure();

                if (attempt < maxAttempts) {
                    long backoffMs = baseBackoffMs * (long) Math.pow(2, attempt - 1);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        logManager.markFailed(txId, "Rollback failed after " + maxAttempts + " attempts");
        return false;
    }

    private EntityManager findEntityManagerForEntity(String entityClassName) {
        try {
            Class<?> entityClass = Class.forName(entityClassName);

            for (EntityManager em : allEntityManagers.values()) {
                try {
                    em.getMetamodel().entity(entityClass);
                    return em;
                } catch (IllegalArgumentException e) {
                    // Not managed by this EM
                }
            }
        } catch (Exception e) {
            log.warn("Cannot find EM for: {}", entityClassName);
        }

        return allEntityManagers.values().iterator().next();
    }

    private void commit(TransactionLog txLog) {
        try {
            logManager.markCommitted(txLog.getTxId());
        } catch (Exception e) {
            log.error("Failed to mark as committed: {}", txLog.getTxId(), e);
        }
    }

    private LockManager.LockHandle acquireLock(String lockKey) throws Exception {
        long waitTime = properties.getLock().getWaitTimeMs();
        long leaseTime = properties.getLock().getLeaseTimeMs();

        log.debug("Acquiring lock: {}", lockKey);

        LockManager.LockHandle lock = lockManager.tryLock(lockKey, waitTime, leaseTime);

        if (!lock.isAcquired()) {
            throw new IllegalStateException("Cannot acquire lock: " + lockKey);
        }

        return lock;
    }

    private String generateBusinessKey(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();

        Object[] args = pjp.getArgs();
        String argInfo = args.length > 0 ? String.valueOf(args[0]) : "noargs";

        return String.format("%s.%s(%s)", className, methodName, argInfo);
    }

    private String evaluateExpression(String expr, ProceedingJoinPoint pjp) {
        if (expr == null || expr.trim().isEmpty()) {
            return null;
        }

        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Object[] args = pjp.getArgs();
            String[] paramNames = signature.getParameterNames();

            context.setVariable("args", args);
            for (int i = 0; i < args.length; i++) {
                context.setVariable("a" + i, args[i]);
                if (paramNames != null && i < paramNames.length) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            Object value = parser.parseExpression(expr).getValue(context);
            return value != null ? value.toString() : null;

        } catch (Exception e) {
            log.warn("Failed to evaluate: {}", expr);
            return expr;
        }
    }

    public static class ValidationException extends RuntimeException {
        private final ValidationResult validationResult;

        public ValidationException(String message, ValidationResult validationResult) {
            super(message);
            this.validationResult = validationResult;
        }

        public ValidationResult getValidationResult() {
            return validationResult;
        }
    }
}