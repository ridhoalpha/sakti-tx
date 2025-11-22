package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.core.idempotency.IdempotencyManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.IdempotencyException;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiIdempotent;
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
public class SaktiIdempotentAspect {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiIdempotentAspect.class);
    
    private final IdempotencyManager idempotencyManager;
    private final SaktiTxProperties properties;
    private final DragonflyHealthIndicator healthIndicator;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public SaktiIdempotentAspect(IdempotencyManager idempotencyManager,
                                SaktiTxProperties properties,
                                DragonflyHealthIndicator healthIndicator) {
        this.idempotencyManager = idempotencyManager;
        this.properties = properties;
        this.healthIndicator = healthIndicator;
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiIdempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.getIdempotency().isEnabled() || healthIndicator.isCircuitOpen()) {
            log.debug("Idempotency disabled or circuit open - executing without check");
            return pjp.proceed();
        }
        
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        SaktiIdempotent annotation = method.getAnnotation(SaktiIdempotent.class);
        
        StandardEvaluationContext context = createSpelContext(pjp);
        String idempKey = evaluateExpression(annotation.key(), context, null);
        
        if (idempKey == null || idempKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key cannot be null or empty");
        }
        
        if (idempotencyManager.exists(idempKey)) {
            log.warn("Duplicate request detected: {}", idempKey);
            throw new IdempotencyException(
                "Request sudah diproses sebelumnya. Refresh halaman untuk melihat data terbaru.");
        }
        
        long ttl = annotation.ttlSeconds() > 0 ? 
            annotation.ttlSeconds() : properties.getIdempotency().getTtlSeconds();
        
        idempotencyManager.markProcessing(idempKey, ttl);
        
        try {
            Object result = pjp.proceed();
            idempotencyManager.markCompleted(idempKey, ttl);
            return result;
        } catch (Exception e) {
            idempotencyManager.rollback(idempKey);
            throw e;
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