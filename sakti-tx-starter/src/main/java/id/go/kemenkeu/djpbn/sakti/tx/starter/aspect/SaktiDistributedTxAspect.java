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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;

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
        
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        SaktiDistributedTx annotation = method.getAnnotation(SaktiDistributedTx.class);
        
        String businessKey = generateBusinessKey(pjp);
        TransactionLog txLog = logManager.createLog(businessKey);
        
        log.debug("Distributed transaction started: {} (business: {})", 
            txLog.getTxId(), businessKey);
        
        // Setup entity tracking context
        EntityOperationListener.EntityOperationContext trackingContext = 
            new EntityOperationListener.EntityOperationContext(true);
        EntityOperationListener.setContext(trackingContext);
        
        LockManager.LockHandle lock = null;
        
        try {
            if (!annotation.lockKey().isEmpty() && lockManager != null) {
                String lockKey = evaluateExpression(annotation.lockKey(), pjp);
                lock = acquireLock(lockKey);
            }
            
            Object result = pjp.proceed();
            
            // Collect all tracked operations
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
            }
            
            commit(txLog);
            
            log.debug("Distributed transaction committed: {} ({} operations)", 
                txLog.getTxId(), txLog.getOperations().size());
            
            return result;
            
        } catch (Throwable error) {
            log.error("Distributed transaction failed: {} - {}", 
                txLog.getTxId(), error.getMessage());
            
            rollback(txLog, error);
            
            throw new TransactionRollbackException(
                "Transaction failed and rolled back: " + error.getMessage(), error);
            
        } finally {
            if (lock != null) {
                lock.release();
            }
            
            EntityOperationListener.clearContext();
        }
    }
    
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
    
    private void commit(TransactionLog txLog) {
        try {
            logManager.markCommitted(txLog.getTxId());
        } catch (Exception e) {
            log.error("Failed to mark transaction as committed", e);
        }
    }
    
    private void rollback(TransactionLog txLog, Throwable originalError) {
        String txId = txLog.getTxId();
        
        try {
            log.warn("Starting rollback: {} ({} operations to compensate)", 
                txId, txLog.getOperations().size());
            
            logManager.markRollingBack(txId, originalError.getMessage());
            
            if (txLog.getOperations().isEmpty()) {
                log.info("No operations to rollback");
                logManager.markRolledBack(txId);
                return;
            }
            
            int maxRetries = 3;
            Exception lastException = null;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    log.info("Rollback attempt {}/{}", attempt, maxRetries);
                    compensator.rollback(txLog);
                    logManager.markRolledBack(txId);
                    log.info("Rollback completed successfully on attempt {}", attempt);
                    return;
                    
                } catch (Exception e) {
                    lastException = e;
                    log.error("Rollback attempt {} failed: {}", attempt, e.getMessage());
                    
                    if (attempt < maxRetries) {
                        Thread.sleep(1000 * attempt);
                    }
                }
            }
            
            log.error("CRITICAL: Rollback failed after {} attempts", maxRetries);
            logManager.markFailed(txId, "Rollback failed: " + lastException.getMessage());
            
        } catch (Exception e) {
            log.error("CRITICAL: Exception during rollback process", e);
            logManager.markFailed(txId, "Rollback exception: " + e.getMessage());
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
        
        log.debug("Lock acquired: {}", lockKey);
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
            log.warn("Failed to evaluate expression: {}, using literal", expr);
            return expr;
        }
    }
}