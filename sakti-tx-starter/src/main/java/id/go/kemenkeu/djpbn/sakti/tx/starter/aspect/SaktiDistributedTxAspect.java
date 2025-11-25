package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.TransactionRollbackException;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedTransactionContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(0) // Run FIRST, before repository interceptor
public class SaktiDistributedTxAspect {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiDistributedTxAspect.class);
    
    private final SaktiTxProperties properties;
    private final TransactionLogManager logManager;
    private final CompensatingTransactionExecutor compensator;
    private final LockManager lockManager; // Optional
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public SaktiDistributedTxAspect(SaktiTxProperties properties,
                                   TransactionLogManager logManager,
                                   CompensatingTransactionExecutor compensator,
                                   @Autowired(required = false) LockManager lockManager) {
        this.properties = properties;
        this.logManager = logManager;
        this.compensator = compensator;
        this.lockManager = lockManager;
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        
        if (!properties.getMultiDb().isEnabled()) {
            log.debug("Multi-DB transaction disabled - executing normally");
            return pjp.proceed();
        }
        
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        SaktiDistributedTx annotation = method.getAnnotation(SaktiDistributedTx.class);
        
        // Generate business key from method + args
        String businessKey = generateBusinessKey(pjp);
        
        // Start distributed transaction
        DistributedTransactionContext ctx = DistributedTransactionContext.start(businessKey, logManager);
        
        log.info("Distributed transaction started: {} (business: {})", 
            ctx.getTransactionId(), businessKey);
        
        LockManager.LockHandle lock = null;
        
        try {
            // Acquire lock if specified
            if (!annotation.lockKey().isEmpty() && lockManager != null) {
                String lockKey = evaluateExpression(annotation.lockKey(), pjp);
                lock = acquireLock(lockKey);
            }
            
            // Execute business logic
            // RepositoryOperationInterceptor will track all operations automatically
            Object result = pjp.proceed();
            
            // Check if rollback was requested
            if (ctx.isRollbackOnly()) {
                throw new TransactionRollbackException("Transaction marked as rollback-only");
            }
            
            // Success - commit transaction
            commit(ctx);
            
            log.info("Distributed transaction committed: {} ({} operations)", 
                ctx.getTransactionId(), ctx.getOperationCount());
            
            return result;
            
        } catch (Throwable error) {
            // Failure - rollback transaction
            log.error("Distributed transaction failed: {} - {}", 
                ctx.getTransactionId(), error.getMessage());
            
            rollback(ctx, error);
            
            throw new TransactionRollbackException(
                "Transaction failed and rolled back: " + error.getMessage(), error);
            
        } finally {
            // Release lock
            if (lock != null) {
                lock.release();
            }
            
            // Clear context
            ctx.markCompleted();
            DistributedTransactionContext.clear();
        }
    }
    
    /**
     * Commit transaction
     */
    private void commit(DistributedTransactionContext ctx) {
        try {
            logManager.markCommitted(ctx.getTransactionId());
            log.info("Transaction log committed: {}", ctx.getTransactionId());
            
        } catch (Exception e) {
            log.error("Failed to mark transaction as committed (data is OK, log update failed)", e);
        }
    }
    
    /**
     * Rollback transaction
     */
    private void rollback(DistributedTransactionContext ctx, Throwable originalError) {
        String txId = ctx.getTransactionId();
        
        try {
            log.warn("Starting rollback: {} ({} operations to compensate)", 
                txId, ctx.getOperationCount());
            
            // Mark as rolling back
            logManager.markRollingBack(txId, originalError.getMessage());
            
            // Execute compensating transactions
            TransactionLog txLog = ctx.getTransactionLog();
            
            if (txLog.getOperations().isEmpty()) {
                log.info("No operations to rollback");
                logManager.markRolledBack(txId);
                return;
            }
            
            // Retry rollback up to 3 times
            int maxRetries = 3;
            Exception lastException = null;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    log.info("Rollback attempt {}/{}", attempt, maxRetries);
                    
                    compensator.rollback(txLog);
                    
                    // Success
                    logManager.markRolledBack(txId);
                    log.info("Rollback completed successfully on attempt {}", attempt);
                    return;
                    
                } catch (Exception e) {
                    lastException = e;
                    log.error("Rollback attempt {} failed: {}", attempt, e.getMessage());
                    
                    if (attempt < maxRetries) {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    }
                }
            }
            
            // All retries failed
            log.error("CRITICAL: Rollback failed after {} attempts", maxRetries);
            logManager.markFailed(txId, "Rollback failed: " + lastException.getMessage());
            
            // Alert monitoring system
            alertFailedTransaction(txId, lastException);
            
        } catch (Exception e) {
            log.error("CRITICAL: Exception during rollback process", e);
            logManager.markFailed(txId, "Rollback exception: " + e.getMessage());
        }
    }
    
    /**
     * Acquire distributed lock
     */
    private LockManager.LockHandle acquireLock(String lockKey) throws Exception {
        long waitTime = properties.getLock().getWaitTimeMs();
        long leaseTime = properties.getLock().getLeaseTimeMs();
        
        log.debug("Acquiring lock: {}", lockKey);
        
        LockManager.LockHandle lock = lockManager.tryLock(lockKey, waitTime, leaseTime);
        
        if (!lock.isAcquired()) {
            throw new IllegalStateException("Cannot acquire lock: " + lockKey);
        }
        
        log.debug("Lock acquired: {}", lockKey);
        return lock;
    }
    
    /**
     * Generate business key from method invocation
     */
    private String generateBusinessKey(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        
        // Include first arg if available (usually ID or key)
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
    
    /**
     * Alert monitoring system about failed transaction
     */
    private void alertFailedTransaction(String txId, Exception error) {
        // Implement integration with monitoring system
        // E.g., send to Slack, PagerDuty, email, etc.
        log.error("ALERT: Transaction {} requires MANUAL INTERVENTION", txId);
        log.error("Check failed transactions queue: sakti:txlog:failed:{}", txId);
        
        // TODO: Integrate with your monitoring system
        // monitoringService.alertCritical("Failed Transaction", txId, error);
    }
}