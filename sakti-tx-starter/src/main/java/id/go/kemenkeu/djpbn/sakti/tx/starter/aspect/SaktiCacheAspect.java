package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import com.fasterxml.jackson.core.type.TypeReference;
import id.go.kemenkeu.djpbn.sakti.tx.core.cache.CacheManager;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiCache;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

@Aspect
@Component
public class SaktiCacheAspect {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiCacheAspect.class);
    
    private final CacheManager saktiCacheManager;
    private final SaktiTxProperties properties;
    private final RedissonClient redissonClient;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public SaktiCacheAspect(CacheManager saktiCacheManager,
                           SaktiTxProperties properties,
                           RedissonClient redissonClient) {
        this.saktiCacheManager = saktiCacheManager;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiCache)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.getCache().isEnabled() || !isRedisHealthy()) {
            log.debug("Cache disabled or Redis unhealthy - executing without cache");
            return pjp.proceed();
        }
        
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        SaktiCache annotation = method.getAnnotation(SaktiCache.class);
        
        StandardEvaluationContext context = createSpelContext(pjp);
        String cacheKey = evaluateExpression(annotation.key(), context, 
            properties.getCache().getPrefix() + method.getName());
        
        Object cached = null;
        
        if (annotation.returnType() != Object.class) {
            log.debug("Using explicit returnType: {}", annotation.returnType().getSimpleName());
            cached = saktiCacheManager.get(cacheKey, annotation.returnType());
        } else {
            log.debug("Auto-detecting return type from method signature");
            Type genericReturnType = method.getGenericReturnType();
            TypeReference<Object> typeRef = new TypeReference<Object>() {
                @Override
                public Type getType() {
                    return genericReturnType;
                }
            };
            cached = saktiCacheManager.get(cacheKey, typeRef);
        }
        
        if (cached != null) {
            log.debug("Cache hit: {}", cacheKey);
            return cached;
        }
        
        log.debug("Cache miss: {}", cacheKey);
        Object result = pjp.proceed();
        
        if (result != null) {
            long ttl = annotation.ttlSeconds() > 0 ? 
                annotation.ttlSeconds() : properties.getCache().getDefaultTtlSeconds();
            saktiCacheManager.put(cacheKey, result, ttl);
        }
        
        return result;
    }
    
    private boolean isRedisHealthy() {
        if (redissonClient == null) {
            return false;
        }
        
        try {
            return redissonClient.getNodesGroup().pingAll();
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return false;
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