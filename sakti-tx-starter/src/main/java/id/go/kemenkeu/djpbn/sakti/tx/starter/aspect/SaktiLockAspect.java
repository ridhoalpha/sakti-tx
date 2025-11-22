package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.LockAcquisitionException;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiLock;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import id.go.kemenkeu.djpbn.sakti.tx.starter.health.DragonflyHealthIndicator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class SaktiLockAspect {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiLockAspect.class);
    
    private final LockManager lockManager;
    private final SaktiTxProperties properties;
    private final DragonflyHealthIndicator healthIndicator;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public SaktiLockAspect(LockManager lockManager,
                          SaktiTxProperties properties,
                          DragonflyHealthIndicator healthIndicator) {
        this.lockManager = lockManager;
        this.properties = properties;
        this.healthIndicator = healthIndicator;
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiLock)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.getLock().isEnabled() || healthIndicator.isCircuitOpen()) {
            log.warn("Lock disabled or circuit open - executing without lock");
            return pjp.proceed();
        }
        
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        SaktiLock annotation = method.getAnnotation(SaktiLock.class);
        
        StandardEvaluationContext context = createSpelContext(pjp);
        String lockKey = evaluateExpression(annotation.key(), context, 
            properties.getLock().getPrefix() + method.getName());
        
        long waitTime = annotation.waitTimeMs() > 0 ? 
            annotation.waitTimeMs() : properties.getLock().getWaitTimeMs();
        long leaseTime = annotation.leaseTimeMs() > 0 ? 
            annotation.leaseTimeMs() : properties.getLock().getLeaseTimeMs();
        
        log.debug("Acquiring lock: {} (wait: {}ms, lease: {}ms)", lockKey, waitTime, leaseTime);
        
        LockManager.LockHandle lock = null;
        try {
            lock = lockManager.tryLock(lockKey, waitTime, leaseTime);
            
            if (!lock.isAcquired()) {
                throw new LockAcquisitionException(
                    "Data sedang diproses. Silakan tunggu beberapa saat.");
            }
            
            return pjp.proceed();
            
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
    }
    
    private StandardEvaluationContext createSpelContext(ProceedingJoinPoint pjp) {
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
        
        return context;
    }
    
    private String evaluateExpression(String expr, StandardEvaluationContext context, 
                                     String fallback) {
        if (expr == null || expr.trim().isEmpty()) {
            return fallback;
        }
        
        try {
            Object value = parser.parseExpression(expr).getValue(context);
            return value != null ? value.toString() : fallback;
        } catch (Exception e) {
            log.warn("Failed to evaluate expression: {}, using literal", expr);
            return expr;
        }
    }
}