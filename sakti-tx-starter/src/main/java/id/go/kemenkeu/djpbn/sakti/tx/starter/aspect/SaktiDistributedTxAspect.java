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

/**
 * ENHANCED: Distributed Transaction Aspect with validation and observability
 * 
 * @version 2.0.0
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
        this.allEntityManagers = emMapper.getAllEntityManagers();
        this.transactionManagers = transactionManagers != null ? transactionManagers : new HashMap<>();
        
        log.info("SaktiDistributedTxAspect initialized (v2.0 - with validation & metrics)");
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        
        if (!properties.getMultiDb().isEnabled()) {
            return pjp.proceed();
        }
        
        Instant startTime = Instant.now();
        metrics.recordTransactionStart();
        
        TransactionLog txLog = null;
        EntityOperationListener.EntityOperationContext operationContext = null;
        LockManager.LockHandle lock = null;
        Map<String, TransactionStatus> activeTransactions = new HashMap<>();
        String txId = null;
        SaktiTxContext context = null;
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 1: SETUP - Create SaktiTxContext
            // ═══════════════════════════════════════════════════════════════
            
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            SaktiDistributedTx annotation = method.getAnnotation(SaktiDistributedTx.class);
            
            String businessKey = generateBusinessKey(pjp);
            
            // Create SaktiTxContext (new context model)
            context = new SaktiTxContext.Builder()
                .businessKey(businessKey)
                .phase(TransactionPhase.CREATED)
                .build();
            
            txId = context.getTxId();
            
            // Bind to thread
            SaktiTxContextHolder.set(context);
            
            // Create transaction log (for persistence)
            txLog = logManager.createLog(businessKey);
            
            MDC.put("txId", txId);
            MDC.put("businessKey", businessKey);
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction STARTED (v2.0)");
            log.info("Transaction ID: {}", txId);
            log.info("Business Key: {}", businessKey);
            log.info("Method: {}.{}", 
                signature.getDeclaringType().getSimpleName(), 
                signature.getName());
            log.info("═══════════════════════════════════════════════════════════");
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 2: ENTITY TRACKING CONTEXT
            // ═══════════════════════════════════════════════════════════════
            
            EntityOperationListener.EntityOperationContext existingCtx = 
                EntityOperationListener.getOperationContext();
            
            if (existingCtx != null) {
                log.warn("WARNING: Operation context already exists! Cleaning...");
                EntityOperationListener.clearOperationContext();
            }
            
            operationContext = new EntityOperationListener.EntityOperationContext(true);
            EntityOperationListener.setOperationContext(operationContext);
            
            // Update phase
            context = context.withPhase(TransactionPhase.COLLECTING);
            SaktiTxContextHolder.update(context);
            
            log.debug("Entity tracking context initialized");
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 3: START TRANSACTIONS & ENLIST RESOURCES
            // ═══════════════════════════════════════════════════════════════
            
            startAllTransactions(activeTransactions);
            
            // Enlist resources in context
            for (String datasource : activeTransactions.keySet()) {
                ResourceEnlistment resource = new ResourceEnlistment(datasource, "DATABASE");
                context = context.withResource(resource);
            }
            SaktiTxContextHolder.update(context);
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 4: ACQUIRE LOCK
            // ═══════════════════════════════════════════════════════════════
            
            if (!annotation.lockKey().isEmpty() && lockManager != null) {
                String lockKey = evaluateExpression(annotation.lockKey(), pjp);
                lock = acquireLock(lockKey);
                
                // Record lock in context
                context = context.withLock(lockKey);
                SaktiTxContextHolder.update(context);
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 5: EXECUTE BUSINESS LOGIC
            // ═══════════════════════════════════════════════════════════════
            
            log.debug("Executing business logic...");
            Object result = pjp.proceed();
            log.debug("Business logic completed");
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 6: FLUSH & COLLECT OPERATIONS
            // ═══════════════════════════════════════════════════════════════
            
            flushAllEntityManagers();
            
            List<EntityOperationListener.ConfirmedOperation> operations = 
                operationContext.getConfirmedOperations();
            
            log.debug("Collected {} operations from tracking context", operations.size());
            
            // Save to transaction log
            for (EntityOperationListener.ConfirmedOperation op : operations) {
                EntityManager em = findEntityManagerForEntity(op.entityClass);
                String datasource = emMapper.getDatasourceName(em);
                
                txLog.recordOperation(
                    datasource,
                    op.type,
                    op.entityClass,
                    op.entityId,
                    op.snapshot
                );
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 7: PRE-COMMIT VALIDATION (NEW!)
            // ═══════════════════════════════════════════════════════════════
            
            context = context.withPhase(TransactionPhase.VALIDATING);
            SaktiTxContextHolder.update(context);
            
            log.info("Starting pre-commit validation...");
            ValidationResult validation = preCommitValidator.validate(context);
            
            if (!validation.canProceed()) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("PRE-COMMIT VALIDATION FAILED");
                log.error("═══════════════════════════════════════════════════════════");
                
                for (ValidationIssue issue : validation.getErrors()) {
                    log.error("  - [ERROR] {}: {}", issue.getCode(), issue.getMessage());
                }
                
                log.error("Transaction ABORTED before commit - databases still consistent");
                log.error("═══════════════════════════════════════════════════════════");
                
                // Rollback Spring transactions (no compensation needed)
                rollbackAllTransactions(activeTransactions);
                
                Duration duration = Duration.between(startTime, Instant.now());
                metrics.recordTransactionRolledBack(duration);
                
                throw new ValidationException("Pre-commit validation failed", validation);
            }
            
            // Log warnings
            for (ValidationIssue issue : validation.getWarnings()) {
                log.warn("  - [WARNING] {}: {}", issue.getCode(), issue.getMessage());
            }
            
            // Log risk level
            log.info("Validation passed - Risk Level: {}", validation.getOverallRisk());
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 8: PREPARED → READY TO COMMIT
            // ═══════════════════════════════════════════════════════════════
            
            context = context.withPhase(TransactionPhase.PREPARED);
            SaktiTxContextHolder.update(context);
            
            // Mark resources as prepared
            for (ResourceEnlistment resource : context.getResources()) {
                resource.setPrepared(true);
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 9: COMMIT TRANSACTIONS
            // ═══════════════════════════════════════════════════════════════
            
            context = context.withPhase(TransactionPhase.COMMITTING);
            SaktiTxContextHolder.update(context);
            
            commitAllTransactions(activeTransactions);
            
            context = context.withPhase(TransactionPhase.COMMITTED);
            SaktiTxContextHolder.update(context);
            
            commit(txLog);
            
            Duration duration = Duration.between(startTime, Instant.now());
            metrics.recordTransactionCommitted(duration);
            
            // Record risk metrics
            for (Map.Entry<id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag, Integer> entry : 
                    context.getRiskMetrics().entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    metrics.recordRiskFlag(entry.getKey());
                }
            }
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction COMMITTED");
            log.info("Transaction ID: {}", txId);
            log.info("Operations: {}", operations.size());
            log.info("Duration: {}ms", duration.toMillis());
            log.info("Risk Level: {}", context.getOverallRiskLevel());
            log.info("═══════════════════════════════════════════════════════════");
            
            return result;
            
        } catch (Throwable error) {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 10: ERROR HANDLING
            // ═══════════════════════════════════════════════════════════════
            
            String errorTxId = txId != null ? txId : "unknown";
            int operationCount = txLog != null ? txLog.getOperations().size() : 0;
            
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Distributed Transaction FAILED");
            log.error("Transaction ID: {}", errorTxId);
            log.error("Operations Recorded: {}", operationCount);
            log.error("Error: {}", error.getMessage());
            log.error("═══════════════════════════════════════════════════════════");
            
            // Update phase
            if (context != null) {
                context = context.withPhase(TransactionPhase.ROLLING_BACK);
                SaktiTxContextHolder.update(context);
            }
            
            rollbackAllTransactions(activeTransactions);
            
            boolean rollbackSuccess = false;
            if (txLog != null) {
                rollbackSuccess = rollbackWithRetry(txLog, error);
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
            // ═══════════════════════════════════════════════════════════════
            
            ultimateCleanup(lock, context, txId);
        }
    }

    private void ultimateCleanup(LockManager.LockHandle lock,
            SaktiTxContext context, String txId) {

        long threadId = Thread.currentThread().getId();

        log.trace("ULTIMATE CLEANUP START - Thread: {}", threadId);

        // Step 1: Release lock
        try {
            if (lock != null) {
                lock.release();
                log.trace("  [1/4] ✓ Lock released");
            }
        } catch (Throwable e) {
            log.error("  [1/4] ✗ Lock release failed", e);
        }
        // Step 2: Clear operation context
        try {
            EntityOperationListener.clearOperationContext();
            log.trace("  [2/4] ✓ Operation context cleared");
        } catch (Throwable e) {
            log.error("  [2/4] ✗ Operation context cleanup failed", e);
        }

        // Step 3: Clear TX context
        try {
            SaktiTxContextHolder.clear();
            log.trace("  [3/4] ✓ TX context cleared");
        } catch (Throwable e) {
            log.error("  [3/4] ✗ TX context cleanup failed", e);
        }

        // Step 4: Clear MDC
        try {
            MDC.remove("txId");
            MDC.remove("businessKey");
            log.trace("  [4/4] ✓ MDC cleared");
        } catch (Throwable e) {
            log.error("  [4/4] ✗ MDC cleanup failed", e);
        }

        log.trace("ULTIMATE CLEANUP END");
    }

    // ========================================================================
    // Helper methods (mostly unchanged from original)
    // ========================================================================

    private void startAllTransactions(Map<String, TransactionStatus> activeTransactions) {
        // Original implementation...
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