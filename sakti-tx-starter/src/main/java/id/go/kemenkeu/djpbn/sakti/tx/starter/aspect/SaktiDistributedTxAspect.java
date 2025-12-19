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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.util.*;

/**
 * ULTIMATE FIX: Guaranteed ThreadLocal cleanup dengan single cleanup point
 * 
 * @version 1.0.3-FINAL
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
    
    private final Map<String, PlatformTransactionManager> transactionManagers;
    private final Map<String, EntityManager> allEntityManagers;
    
    public SaktiDistributedTxAspect(
            SaktiTxProperties properties,
            TransactionLogManager logManager,
            CompensatingTransactionExecutor compensator,
            EntityManagerDatasourceMapper emMapper,
            @Autowired(required = false) LockManager lockManager,
            @Autowired(required = false) Map<String, PlatformTransactionManager> transactionManagers) {
        
        this.properties = properties;
        this.logManager = logManager;
        this.compensator = compensator;
        this.emMapper = emMapper;
        this.lockManager = lockManager;
        this.allEntityManagers = emMapper.getAllEntityManagers();
        this.transactionManagers = transactionManagers != null ? transactionManagers : new HashMap<>();
        
        log.info("SaktiDistributedTxAspect initialized");
        log.info("   EntityManagers: {}", allEntityManagers.keySet());
        log.info("   TransactionManagers: {}", this.transactionManagers.keySet());
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        
        if (!properties.getMultiDb().isEnabled()) {
            return pjp.proceed();
        }
        
        TransactionLog txLog = null;
        EntityOperationListener.EntityOperationContext trackingContext = null;
        LockManager.LockHandle lock = null;
        Map<String, TransactionStatus> activeTransactions = new HashMap<>();
        String txId = null;
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 1: SETUP
            // ═══════════════════════════════════════════════════════════════
            
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            SaktiDistributedTx annotation = method.getAnnotation(SaktiDistributedTx.class);
            
            String businessKey = generateBusinessKey(pjp);
            txLog = logManager.createLog(businessKey);
            txId = txLog.getTxId();
            
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
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 2: ENTITY TRACKING CONTEXT
            // ═══════════════════════════════════════════════════════════════
            
            // Check if context already exists (shouldn't happen, but defensive)
            EntityOperationListener.EntityOperationContext existingCtx = 
                EntityOperationListener.getContext();
            
            if (existingCtx != null) {
                log.warn("WARNING: Context already exists! Cleaning it first...");
                EntityOperationListener.clearContext();
            }
            
            trackingContext = new EntityOperationListener.EntityOperationContext(true);
            EntityOperationListener.setContext(trackingContext);
            
            log.debug("Entity tracking context initialized - thread: {}", 
                Thread.currentThread().getId());
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 3: START TRANSACTIONS
            // ═══════════════════════════════════════════════════════════════
            
            startAllTransactions(activeTransactions);
            
            if (!activeTransactions.isEmpty()) {
                log.info("Started {} Spring transactions", activeTransactions.size());
            } else {
                log.warn("═══════════════════════════════════════════════════════════");
                log.warn("WARNING: No Spring transactions started!");
                log.warn("This will cause TransactionRequiredException");
                log.warn("═══════════════════════════════════════════════════════════");
                log.warn("SOLUTION:");
                log.warn("1. Ensure @EnableTransactionManagement in your @Configuration");
                log.warn("2. Ensure PlatformTransactionManager beans exist:");
                log.warn("   - db1TransactionManager");
                log.warn("   - db2TransactionManager");
                log.warn("   - etc.");
                log.warn("3. OR add @Transactional to method: {}", method.getName());
                log.warn("═══════════════════════════════════════════════════════════");
            }
            
            // ═══════════════════════════════════════════════════════════════
            // PHASE 4: ACQUIRE LOCK
            // ═══════════════════════════════════════════════════════════════
            
            if (!annotation.lockKey().isEmpty() && lockManager != null) {
                String lockKey = evaluateExpression(annotation.lockKey(), pjp);
                lock = acquireLock(lockKey);
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
            
            // CRITICAL FIX: Get operations from context BEFORE clearing it!
            List<EntityOperationListener.ConfirmedOperation> operations = 
                trackingContext.getConfirmedOperations();
            
            log.debug("Collected {} operations from tracking context", operations.size());
            
            // Save operations to transaction log
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
            // PHASE 7: COMMIT TRANSACTIONS
            // ═══════════════════════════════════════════════════════════════
            
            commitAllTransactions(activeTransactions);
            commit(txLog);
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Distributed Transaction COMMITTED");
            log.info("Transaction ID: {}", txId);
            log.info("Operations: {}", operations.size());
            log.info("Databases: {}", allEntityManagers.keySet());
            log.info("═══════════════════════════════════════════════════════════");
            
            return result;
            
        } catch (Throwable error) {
            // ═══════════════════════════════════════════════════════════════
            // PHASE 8: ERROR HANDLING
            // ═══════════════════════════════════════════════════════════════
            
            String errorTxId = txId != null ? txId : "unknown";
            int operationCount = txLog != null ? txLog.getOperations().size() : 0;
            
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Distributed Transaction FAILED");
            log.error("Transaction ID: {}", errorTxId);
            log.error("Operations Recorded: {}", operationCount);
            log.error("Error Type: {}", error.getClass().getName());
            log.error("Error Message: {}", error.getMessage());
            log.error("═══════════════════════════════════════════════════════════");
            
            rollbackAllTransactions(activeTransactions);
            
            boolean rollbackSuccess = false;
            if (txLog != null) {
                rollbackSuccess = rollbackWithRetry(txLog, error);
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
            // PHASE 9: GUARANTEED CLEANUP (CRITICAL!)
            // ═══════════════════════════════════════════════════════════════
            
            // This MUST execute no matter what!
            ultimateCleanup(lock, txId);
        }
    }
    
    /**
     * ═══════════════════════════════════════════════════════════════════════════
     * ULTIMATE CLEANUP: 100% Guaranteed, No Dependencies, No Conditions
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * REPLACE existing guaranteedCleanup() method with this method!
     * 
     * Location: SaktiDistributedTxAspect.java, line ~281
     * 
     * PRINCIPLES:
     * 1. NO flags/conditions - ALWAYS cleanup everything
     * 2. Detection INDEPENDENT of creation tracking  
     * 3. Multiple strategies (defense in depth)
     * 4. Verification after each step
     * 5. Emergency fallback if all else fails
     */
    private void ultimateCleanup(LockManager.LockHandle lock, String txId) {
        
        long threadId = Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();
        
        log.trace("════════════════════════════════════════════════════════════");
        log.trace("ULTIMATE CLEANUP START - Thread: {} ({})", threadId, threadName);
        log.trace("════════════════════════════════════════════════════════════");
        
        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Release Lock (if exists)
        // ═══════════════════════════════════════════════════════════════
        try {
            if (lock != null) {
                lock.release();
                log.trace("  [1/4] ✓ Lock released");
            } else {
                log.trace("  [1/4] - No lock to release");
            }
        } catch (Throwable e) {
            log.error("  [1/4] ✗ Lock release failed", e);
            // Continue cleanup even if lock release fails
        }
        
        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Clear EntityOperationListener Context
        //         CRITICAL: This is the main leak source!
        // ═══════════════════════════════════════════════════════════════
        try {
            // Strategy 1: Check if context exists (NO FLAG DEPENDENCY!)
            EntityOperationListener.EntityOperationContext ctx = 
                EntityOperationListener.getContext();
            
            if (ctx == null) {
                log.trace("  [2/4] - No context to clear");
            } else {
                log.trace("  [2/4] Context found - clearing...");
                
                // Strategy 2: Normal clear
                EntityOperationListener.clearContext();
                
                // Strategy 3: IMMEDIATE VERIFICATION (don't trust clearContext!)
                EntityOperationListener.EntityOperationContext afterClear = 
                    EntityOperationListener.getContext();
                
                if (afterClear != null) {
                    
                    // Strategy 4: Force clear via context method
                    if (!afterClear.isCleared()) {
                        log.warn("  [2/4] Forcing context.forceClear()...");
                        afterClear.forceCleanData();
                    }
                    
                    // Strategy 5: Direct ThreadLocal manipulation
                    log.warn("  [2/4] Forcing setContext(null) + clearContext()...");
                    EntityOperationListener.setContext(null);
                    EntityOperationListener.clearContext();
                    
                    // Strategy 6: FINAL VERIFICATION
                    EntityOperationListener.EntityOperationContext finalCheck = 
                        EntityOperationListener.getContext();
                    
                    if (finalCheck != null) {
                        
                        // Strategy 7: EMERGENCY - Reflection (last resort)
                        try {
                            java.lang.reflect.Field field = EntityOperationListener.class
                                .getDeclaredField("CONTEXT");
                            field.setAccessible(true);
                            ThreadLocal<?> tl = (ThreadLocal<?>) field.get(null);
                            tl.remove();
                            tl.set(null);
                            tl.remove();
                            
                            log.warn("  [2/4] ⚠ Emergency reflection cleanup applied");
                            
                            // Verify reflection worked
                            EntityOperationListener.EntityOperationContext afterReflect = 
                                EntityOperationListener.getContext();
                            
                            if (afterReflect == null) {
                                log.info("  [2/4] ✓ Reflection cleanup SUCCESSFUL");
                            }
                            
                        } catch (Throwable reflectError) {
                            log.error("  [2/4] ✗✗✗ Emergency reflection cleanup FAILED", reflectError);
                        }
                    } else {
                        log.info("  [2/4] ✓ Context cleared successfully (after retry)");
                    }
                } else {
                    log.trace("  [2/4] ✓ Context cleared successfully (first attempt)");
                }
            }
            
        } catch (Throwable e) {
            log.error("  [2/4] ✗ Context cleanup threw exception", e);
            
            // Emergency fallback - try basic cleanup
            try {
                log.warn("  [2/4] Emergency fallback: basic cleanup...");
                EntityOperationListener.setContext(null);
                EntityOperationListener.clearContext();
            } catch (Throwable ex) {
                log.error("  [2/4] ✗✗ Emergency fallback FAILED", ex);
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Clear MDC (SLF4J Mapped Diagnostic Context)
        // ═══════════════════════════════════════════════════════════════
        try {
            MDC.remove("txId");
            MDC.remove("businessKey");
            log.trace("  [3/4] ✓ MDC cleared");
        } catch (Throwable e) {
            log.error("  [3/4] ✗ MDC clear failed", e);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // STEP 4: FINAL VERIFICATION - Ensure no leaks
        // ═══════════════════════════════════════════════════════════════
        try {
            EntityOperationListener.EntityOperationContext finalCtx = 
                EntityOperationListener.getContext();
            
            if (finalCtx == null) {
                log.trace("  [4/4] ✓ Final verification passed - no leaks detected");
            }
            
        } catch (Throwable e) {
            log.error("  [4/4] ✗ Final verification failed", e);
        }
        
        log.trace("════════════════════════════════════════════════════════════");
        log.trace("ULTIMATE CLEANUP END - Thread: {} ({})", threadId, threadName);
        log.trace("════════════════════════════════════════════════════════════");
    }
    
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
        if (activeTransactions.isEmpty()) {
            return;
        }
        
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
        if (activeTransactions.isEmpty()) {
            return;
        }
        
        log.warn("Rolling back {} transactions", activeTransactions.size());
        
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
        
        log.warn("===============================================================");
        log.warn("Starting ROLLBACK (max {} attempts)", maxAttempts);
        log.warn("Transaction ID: {}", txId);
        log.warn("Operations: {}", txLog.getOperations().size());
        log.warn("===============================================================");
        
        logManager.markRollingBack(txId, originalError.getMessage());
        
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Rollback attempt {}/{}", attempt, maxAttempts);
                
                compensator.rollback(txLog);
                logManager.markRolledBack(txId);
                
                log.info("===============================================================");
                log.info("ROLLBACK SUCCESSFUL on attempt {}/{}", attempt, maxAttempts);
                log.info("Transaction ID: {}", txId);
                log.info("===============================================================");
                
                return true;
                
            } catch (Exception e) {
                lastException = e;
                log.error("Rollback attempt {}/{} FAILED: {}", attempt, maxAttempts, e.getMessage());
                
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
        
        log.error("===============================================================");
        log.error("ROLLBACK FAILED after {} attempts", maxAttempts);
        log.error("Transaction ID: {}", txId);
        log.error("MANUAL INTERVENTION REQUIRED!");
        log.error("===============================================================");
        
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
}