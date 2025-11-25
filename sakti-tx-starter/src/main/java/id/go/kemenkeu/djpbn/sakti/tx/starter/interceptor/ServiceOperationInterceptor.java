package id.go.kemenkeu.djpbn.sakti.tx.starter.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.TrackOperation;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedTransactionContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;


/**
 * Intercept service layer methods dengan @TrackOperation
 * 
 * DIGUNAKAN UNTUK:
 * - Bulk operations (BULK_UPDATE, BULK_DELETE)
 * - Native queries (NATIVE_QUERY)
 * - Stored procedures (STORED_PROCEDURE)
 * 
 * TIDAK DIGUNAKAN untuk entity operations sederhana (INSERT/UPDATE/DELETE)
 * karena sudah di-handle oleh EntityOperationListener
 */
@Aspect
@Component
@Order(1)
public class ServiceOperationInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(ServiceOperationInterceptor.class);
    private final ObjectMapper objectMapper;
    
    public ServiceOperationInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Around("@annotation(trackOperation)")
    public Object intercept(ProceedingJoinPoint pjp, TrackOperation trackOperation) throws Throwable {
        
        DistributedTransactionContext ctx = DistributedTransactionContext.get();
        if (ctx == null || !ctx.isActive()) {
            log.debug("No active distributed transaction context - executing without tracking");
            return pjp.proceed();
        }
        
        OperationType opType = trackOperation.type();
        
        // Complex operations yang perlu manual tracking
        if (isComplexOperation(opType)) {
            log.debug("Tracking complex operation: {} on {}", opType, trackOperation.datasource());
            return trackComplexOperation(pjp, trackOperation, ctx);
        }
        
        // Simple entity operations akan di-handle oleh EntityOperationListener
        log.debug("Simple operation {} - will be tracked by EntityOperationListener", opType);
        return pjp.proceed();
    }
    
    private boolean isComplexOperation(OperationType type) {
        return type == OperationType.BULK_UPDATE ||
               type == OperationType.BULK_DELETE ||
               type == OperationType.NATIVE_QUERY ||
               type == OperationType.STORED_PROCEDURE;
    }
    
    private Object trackComplexOperation(ProceedingJoinPoint pjp, 
                                         TrackOperation trackOperation,
                                         DistributedTransactionContext ctx) throws Throwable {
        
        // Note: Developer must manually call recordBulkOperation(), recordNativeQuery(), etc.
        // inside the method before executing the operation
        // This interceptor just logs the operation type
        
        Object result = pjp.proceed();
        
        log.info("Complex operation {} executed on {}", 
            trackOperation.type(), trackOperation.datasource());
        
        return result;
    }
}