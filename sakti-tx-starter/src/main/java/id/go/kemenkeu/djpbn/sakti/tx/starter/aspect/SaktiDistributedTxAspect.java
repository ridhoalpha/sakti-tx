package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.core.transaction.MultiDbTransactionManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.TransactionRollbackException;
import id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.MultiDbTxContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class SaktiDistributedTxAspect {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiDistributedTxAspect.class);
    
    private final SaktiTxProperties properties;
    
    public SaktiDistributedTxAspect(SaktiTxProperties properties) {
        this.properties = properties;
    }
    
    @Around("@annotation(id.go.kemenkeu.djpbn.sakti.tx.starter.annotation.SaktiDistributedTx)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.getMultiDb().isEnabled()) {
            log.debug("Multi-DB transaction disabled - executing normally");
            return pjp.proceed();
        }
        
        MultiDbTransactionManager txManager = new MultiDbTransactionManager();
        MultiDbTxContextHolder.set(txManager);
        
        try {
            Object result = pjp.proceed();
            txManager.clear();
            log.debug("Multi-DB transaction completed successfully");
            return result;
            
        } catch (Exception e) {
            log.error("Multi-DB transaction failed, executing rollback", e);
            txManager.executeRollback();
            throw new TransactionRollbackException("Transaction failed and rolled back", e);
            
        } finally {
            MultiDbTxContextHolder.clear();
        }
    }
    
    /**
     * Get current transaction manager for manual rollback registration
     */
    public static MultiDbTransactionManager getCurrentTxManager() {
        return MultiDbTxContextHolder.get();
    }
}