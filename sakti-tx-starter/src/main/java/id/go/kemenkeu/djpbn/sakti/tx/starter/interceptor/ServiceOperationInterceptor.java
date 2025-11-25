package id.go.kemenkeu.djpbn.sakti.tx.starter.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.TrackOperation;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedTransactionContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Intercept service layer methods yang di-annotate dengan @TrackOperation
 * Lebih reliable daripada intercept repository proxy
 */
@Aspect
@Component
@Order(1)
public class ServiceOperationInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(ServiceOperationInterceptor.class);
    
    @PersistenceContext
    private EntityManager entityManager;
    
    private final ObjectMapper objectMapper;
    
    public ServiceOperationInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Around("@annotation(trackOperation)")
    public Object intercept(ProceedingJoinPoint pjp, TrackOperation trackOperation) throws Throwable {
        
        DistributedTransactionContext ctx = DistributedTransactionContext.get();
        if (ctx == null || !ctx.isActive()) {
            return pjp.proceed();
        }
        
        Object[] args = pjp.getArgs();
        if (args.length == 0) {
            return pjp.proceed();
        }
        
        Object entity = findEntity(args);
        if (entity == null) {
            return pjp.proceed();
        }
        
        OperationType opType = trackOperation.type();
        Object snapshot = null;
        Object entityIdBefore = null;
        
        // Take snapshot BEFORE operation
        if (opType == OperationType.UPDATE || opType == OperationType.DELETE) {
            entityIdBefore = extractEntityId(entity);
            snapshot = createDeepCopy(entity);
        }
        
        // Execute operation
        Object result = pjp.proceed();
        
        // Get entity ID after operation (for INSERT)
        Object entityId = (opType == OperationType.INSERT) 
            ? extractEntityId(result != null ? result : entity)
            : entityIdBefore;
        
        // Record to transaction log
        ctx.recordOperation(
            trackOperation.datasource(),
            opType,
            entity.getClass().getName(),
            entityId,
            snapshot
        );
        
        log.debug("Tracked {} on {} [id={}] in {}", 
            opType, entity.getClass().getSimpleName(), entityId, trackOperation.datasource());
        
        return result;
    }
    
    private Object findEntity(Object[] args) {
        for (Object arg : args) {
            if (arg != null && hasIdAnnotation(arg.getClass())) {
                return arg;
            }
        }
        return null;
    }
    
    private boolean hasIdAnnotation(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return true;
            }
        }
        return false;
    }
    
    private Object extractEntityId(Object entity) {
        if (entity == null) return null;
        
        try {
            // Find @Id field
            for (Field field : entity.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    return field.get(entity);
                }
            }
            
            // Fallback: try getId()
            try {
                Method getter = entity.getClass().getMethod("getId");
                return getter.invoke(entity);
            } catch (NoSuchMethodException ignored) {}
            
        } catch (Exception e) {
            log.warn("Cannot extract entity ID from {}", entity.getClass().getSimpleName(), e);
        }
        
        return null;
    }
    
    private Object createDeepCopy(Object entity) {
        try {
            // Use Jackson for deep copy
            String json = objectMapper.writeValueAsString(entity);
            return objectMapper.readValue(json, entity.getClass());
        } catch (Exception e) {
            log.error("Cannot create deep copy of {}", entity.getClass().getSimpleName(), e);
            return null;
        }
    }
}