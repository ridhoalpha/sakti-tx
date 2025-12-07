package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.TransactionRollbackException;
import id.go.kemenkeu.djpbn.sakti.tx.core.listener.EntityOperationListener;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.mapper.EntityManagerDatasourceMapper;
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

import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;

/**
 * ENHANCED Aspect untuk distributed transaction
 * 
 * IMPROVEMENTS:
 * - Full context logging dengan MDC
 * - Guaranteed ThreadLocal cleanup
 * - Exponential backoff retry untuk rollback
 * - Detailed error reporting
 * - Better exception propagation
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
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public SaktiDistributedTxAspect(SaktiTxProperties properties,
                                   TransactionLogManager logManager,
                                   CompensatingTransactionExecutor compensator,
                                   EntityManagerDatasourceMapper emMapper,
                                   @Autowired(required = false) LockManager lockManager) {
        this.properties = properties;
        this.logManager = logManager;
        this.compensator = compensator;
        this.emMapper = emMapper;
        this.lockManager = lockManager;
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        
        if (!properties.getMultiDb().isEnabled()) {
            log.debug("Multi-DB transaction disabled - executing normally");
            return pjp.proceed();
        }
        
        // Extract method info
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        SaktiDistributedTx annotation = method.getAnnotation(SaktiDistributedTx.class);
        
        String businessKey = generateBusinessKey(pjp);
        TransactionLog txLog = null;
        EntityOperationListener.EntityOperationContext trackingContext = null;
        LockManager.LockHandle lock = null;
        
        try {
            // Initialize transaction log
            txLog = logManager.createLog(businessKey);
            String txId = txLog.getTxId();
            
            // Set MDC for distributed tracing
            MDC.put("txId", txId);
            MDC.put("businessKey", businessKey);
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction STARTED");
            log.info("Transaction ID: {}", txId);
            log.info("Business Key: {}", businessKey);
            log.info("Method: {}.{}", 
                signature.getDeclaringType().getSimpleName(), 
                signature.getName());
            log.info("═══════════════════════════════════════════════════════════");
            
            // Setup entity tracking context
            trackingContext = new EntityOperationListener.EntityOperationContext(true);
            EntityOperationListener.setContext(trackingContext);
            
            // Acquire distributed lock if needed
            if (!annotation.lockKey().isEmpty() && lockManager != null) {
                String lockKey = evaluateExpression(annotation.lockKey(), pjp);
                lock = acquireLock(lockKey);
            }
            
            // Execute business logic
            Object result = pjp.proceed();
            
            // Collect all tracked operations
            int operationCount = 0;
            for (EntityOperationListener.ConfirmedOperation op : trackingContext.getConfirmedOperations()) {
                EntityManager em = findEntityManagerForEntity(op.entityClass);
                String datasource = emMapper.getDatasourceName(em);
                
                txLog.recordOperation(
                    datasource,
                    op.type,
                    op.entityClass,
                    op.entityId,
                    op.snapshot
                );
                operationCount++;
            }
            
            // Commit transaction
            commit(txLog);
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction COMMITTED");
            log.info("Transaction ID: {}", txId);
            log.info("Operations: {}", operationCount);
            log.info("═══════════════════════════════════════════════════════════");
            
            return result;
            
        } catch (Throwable error) {
            // Enhanced error logging
            String txId = txLog != null ? txLog.getTxId() : "unknown";
            int operationCount = txLog != null ? txLog.getOperations().size() : 0;
            
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Distributed Transaction FAILED");
            log.error("Transaction ID: {}", txId);
            log.error("Business Key: {}", businessKey);
            log.error("Method: {}.{}", 
                signature.getDeclaringType().getSimpleName(), 
                signature.getName());
            log.error("Operations Recorded: {}", operationCount);
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Error Type: {}", error.getClass().getName());
            log.error("Error Message: {}", error.getMessage());
            log.error("═══════════════════════════════════════════════════════════");
            
            // Log each operation for debugging
            if (txLog != null && !txLog.getOperations().isEmpty()) {
                log.error("Operations that will be rolled back:");
                for (TransactionLog.OperationLog op : txLog.getOperations()) {
                    log.error("  → [{}] {} {} on {} [id={}]",
                        op.getSequence(),
                        op.getOperationType(), 
                        op.getEntityClass(), 
                        op.getDatasource(), 
                        op.getEntityId());
                }
            }
            
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Full stacktrace:", error);
            log.error("═══════════════════════════════════════════════════════════");
            
            // Perform rollback with retry
            boolean rollbackSuccess = false;
            if (txLog != null) {
                rollbackSuccess = rollbackWithRetry(txLog, error);
            }
            
            // Throw enhanced exception with context
            throw new TransactionRollbackException(
                String.format("Transaction %s failed and %s: %s. Operations: %d. Original error: %s",
                    txId,
                    rollbackSuccess ? "rolled back successfully" : "ROLLBACK FAILED",
                    rollbackSuccess ? "All changes reverted" : "PARTIAL COMMIT MAY EXIST",
                    operationCount,
                    error.getMessage()),
                error,
                txId,
                operationCount,
                !rollbackSuccess
            );
            
        } finally {
            // CRITICAL: Guaranteed cleanup
            log.trace("Cleaning up transaction resources...");
            
            // Release lock
            if (lock != null) {
                try {
                    lock.release();
                    log.trace("Lock released");
                } catch (Exception e) {
                    log.error("Failed to release lock", e);
                }
            }
            
            // Clear entity operation context
            try {
                EntityOperationListener.clearContext();
                log.trace("Entity operation context cleared");
            } catch (Exception e) {
                log.error("Failed to clear entity operation context", e);
            }
            
            // Clear MDC
            MDC.remove("txId");
            MDC.remove("businessKey");
            
            // Sanity check - verify cleanup
            if (EntityOperationListener.getContext() != null) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("CRITICAL: ThreadLocal context NOT cleared properly!");
                log.error("This indicates a bug in cleanup logic");
                log.error("═══════════════════════════════════════════════════════════");
            }
        }
    }
    
    /**
     * Rollback dengan exponential backoff retry
     * Returns true if rollback successful, false if failed
     */
    private boolean rollbackWithRetry(TransactionLog txLog, Throwable originalError) {
        String txId = txLog.getTxId();
        int maxAttempts = properties.getMultiDb().getMaxRollbackRetries();
        long baseBackoffMs = properties.getMultiDb().getRollbackRetryBackoffMs();
        
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("Starting ROLLBACK with retry (max {} attempts)", maxAttempts);
        log.warn("Transaction ID: {}", txId);
        log.warn("Operations to compensate: {}", txLog.getOperations().size());
        log.warn("═══════════════════════════════════════════════════════════");
        
        logManager.markRollingBack(txId, originalError.getMessage());
        
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Rollback attempt {}/{} for txId: {}", attempt, maxAttempts, txId);
                
                compensator.rollback(txLog);
                logManager.markRolledBack(txId);
                
                log.info("═══════════════════════════════════════════════════════════");
                log.info("✓ ROLLBACK SUCCESSFUL on attempt {}/{}", attempt, maxAttempts);
                log.info("Transaction ID: {}", txId);
                log.info("All {} operations compensated", txLog.getOperations().size());
                log.info("═══════════════════════════════════════════════════════════");
                
                return true;
                
            } catch (Exception e) {
                lastException = e;
                
                log.error("═══════════════════════════════════════════════════════════");
                log.error("✗ Rollback attempt {}/{} FAILED", attempt, maxAttempts);
                log.error("Transaction ID: {}", txId);
                log.error("Error: {}", e.getMessage());
                log.error("═══════════════════════════════════════════════════════════");
                
                if (attempt < maxAttempts) {
                    long backoffMs = baseBackoffMs * (long) Math.pow(2, attempt - 1);
                    log.warn("Waiting {}ms before retry attempt {}...", backoffMs, attempt + 1);
                    
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Retry sleep interrupted");
                        break;
                    }
                }
            }
        }
        
        // All retries failed
        String failureReason = String.format(
            "Rollback failed after %d attempts. Last error: %s. Original error: %s",
            maxAttempts,
            lastException != null ? lastException.getMessage() : "unknown",
            originalError.getMessage()
        );
        
        logManager.markFailed(txId, failureReason);
        
        log.error("═══════════════════════════════════════════════════════════");
        log.error("⚠ CRITICAL: ROLLBACK FAILED AFTER {} ATTEMPTS", maxAttempts);
        log.error("Transaction ID: {}", txId);
        log.error("Operations: {}", txLog.getOperations().size());
        log.error("═══════════════════════════════════════════════════════════");
        log.error("⚠ PARTIAL COMMIT MAY EXIST - MANUAL INTERVENTION REQUIRED!");
        log.error("═══════════════════════════════════════════════════════════");
        log.error("Actions:");
        log.error("  1. Check transaction log: {}", txId);
        log.error("  2. Verify database state manually");
        log.error("  3. Use admin API to retry: POST /admin/transactions/retry/{}", txId);
        log.error("  4. Monitor failed transactions: GET /admin/transactions/failed");
        log.error("═══════════════════════════════════════════════════════════");
        
        return false;
    }
    
    /**
     * Find EntityManager for entity class
     */
    private EntityManager findEntityManagerForEntity(String entityClassName) {
        try {
            Class<?> entityClass = Class.forName(entityClassName);
            
            for (EntityManager em : emMapper.getAllEntityManagers().values()) {
                try {
                    em.getMetamodel().entity(entityClass);
                    return em;
                } catch (IllegalArgumentException e) {
                    // Entity not managed by this EM
                }
            }
            
        } catch (Exception e) {
            log.warn("Cannot find EntityManager for entity: {}", entityClassName);
        }
        
        // Fallback: return first available EM
        return emMapper.getAllEntityManagers().values().iterator().next();
    }
    
    /**
     * Commit transaction
     */
    private void commit(TransactionLog txLog) {
        try {
            logManager.markCommitted(txLog.getTxId());
        } catch (Exception e) {
            log.error("Failed to mark transaction as committed: {}", txLog.getTxId(), e);
        }
    }
    
    /**
     * Acquire distributed lock
     */
    private LockManager.LockHandle acquireLock(String lockKey) throws Exception {
        long waitTime = properties.getLock().getWaitTimeMs();
        long leaseTime = properties.getLock().getLeaseTimeMs();
        
        log.debug("Acquiring distributed lock: {}", lockKey);
        
        LockManager.LockHandle lock = lockManager.tryLock(lockKey, waitTime, leaseTime);
        
        if (!lock.isAcquired()) {
            throw new IllegalStateException("Cannot acquire lock: " + lockKey);
        }
        
        log.debug("Lock acquired: {}", lockKey);
        return lock;
    }
    
    /**
     * Generate business key dari method signature
     */
    private String generateBusinessKey(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        
        Object[] args = pjp.getArgs();
        String argInfo = args.length > 0 ? String.valueOf(args[0]) : "noargs";
        
        return String.format("%s.%s(%s)", className, methodName, argInfo);
    }
    
    /**
     * Evaluate SpEL expression
     */
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
            log.warn("Failed to evaluate expression: {}, using literal", expr);
            return expr;
        }
    }
}