package id.go.kemenkeu.djpbn.sakti.tx.starter.service.impl;

import id.go.kemenkeu.djpbn.sakti.tx.core.exception.IdempotencyException;
import id.go.kemenkeu.djpbn.sakti.tx.core.exception.LockAcquisitionException;
import id.go.kemenkeu.djpbn.sakti.tx.core.idempotency.IdempotencyManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import id.go.kemenkeu.djpbn.sakti.tx.starter.health.DragonflyHealthIndicator;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class DistributedLockServiceImpl implements DistributedLockService {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedLockServiceImpl.class);
    
    private final LockManager lockManager;
    private final IdempotencyManager idempotencyManager;
    private final DragonflyHealthIndicator healthIndicator;
    private final SaktiTxProperties properties;
    
    public DistributedLockServiceImpl(LockManager lockManager,
                                     @Autowired(required = false) IdempotencyManager idempotencyManager,
                                     @Autowired(required = false) DragonflyHealthIndicator healthIndicator,
                                     SaktiTxProperties properties) {
        this.lockManager = lockManager;
        this.idempotencyManager = idempotencyManager;
        this.healthIndicator = healthIndicator;
        this.properties = properties;
    }
    
    @Override
    public <T> T executeWithLock(String lockKey, Supplier<T> action) throws Exception {
        return executeWithLock(lockKey, 
            properties.getLock().getWaitTimeMs(), 
            properties.getLock().getLeaseTimeMs(), 
            action);
    }
    
    @Override
    public <T> T executeWithLock(String lockKey, long waitTimeMs, long leaseTimeMs, 
                                Supplier<T> action) throws Exception {
        boolean circuitOpen = healthIndicator != null && healthIndicator.isCircuitOpen();
        
        if (!properties.getLock().isEnabled() || circuitOpen) {
            log.warn("Lock disabled or circuit open - executing without lock: {}", lockKey);
            return action.get();
        }
        
        LockManager.LockHandle lock = null;
        
        try {
            lock = lockManager.tryLock(lockKey, waitTimeMs, leaseTimeMs);
            
            if (!lock.isAcquired()) {
                throw new LockAcquisitionException(
                    "Data sedang diproses. Silakan tunggu beberapa saat.");
            }
            
            return action.get();
            
        } catch (org.redisson.client.RedisException e) {
            log.error("Redis connection failed for lock: {}. Executing without lock.", lockKey, e);
            return action.get();
            
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
    }
    
    @Override
    public <T> T executeWithLockAndIdempotency(String lockKey, String idempotencyKey,
                                              Supplier<T> action) throws Exception {
        // Check if idempotency is available
        if (idempotencyManager == null || !properties.getIdempotency().isEnabled()) {
            log.warn("IdempotencyManager not available - executing with lock only");
            return executeWithLock(lockKey, action);
        }
        
        // Check if circuit is open
        boolean circuitOpen = healthIndicator != null && healthIndicator.isCircuitOpen();
        
        if (!properties.getLock().isEnabled() || circuitOpen) {
            log.warn("Lock disabled or circuit open - executing without protection");
            return action.get();
        }
        
        // Check idempotency first
        if (idempotencyManager.exists(idempotencyKey)) {
            log.warn("Duplicate request detected: {}", idempotencyKey);
            throw new IdempotencyException(
                "Request sudah diproses sebelumnya. Refresh halaman untuk melihat data terbaru.");
        }
        
        LockManager.LockHandle lock = null;
        boolean idempotencyMarked = false;
        
        try {
            lock = lockManager.tryLock(lockKey, 
                properties.getLock().getWaitTimeMs(), 
                properties.getLock().getLeaseTimeMs());
            
            if (!lock.isAcquired()) {
                throw new LockAcquisitionException(
                    "Data sedang diproses oleh pengguna lain. Silakan coba lagi.");
            }
            
            // Double-check after lock acquired
            if (idempotencyManager.exists(idempotencyKey)) {
                throw new IdempotencyException(
                    "Request sudah diproses. Refresh halaman untuk melihat data terbaru.");
            }
            
            idempotencyManager.markProcessing(idempotencyKey, 
                properties.getIdempotency().getTtlSeconds());
            idempotencyMarked = true;
            
            T result = action.get();
            
            idempotencyManager.markCompleted(idempotencyKey, 
                properties.getIdempotency().getTtlSeconds());
            
            return result;
            
        } catch (IdempotencyException | LockAcquisitionException e) {
            throw e;
            
        } catch (Exception e) {
            log.error("Business logic failed: {}", lockKey, e);
            if (idempotencyMarked && idempotencyManager != null) {
                idempotencyManager.rollback(idempotencyKey);
            }
            throw e;
            
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
    }
}