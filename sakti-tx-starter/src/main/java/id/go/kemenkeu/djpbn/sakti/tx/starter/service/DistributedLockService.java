package id.go.kemenkeu.djpbn.sakti.tx.starter.service;

import id.go.kemenkeu.djpbn.sakti.tx.core.wrapper.CheckedSupplier;

/**
 * High-level service for distributed lock operations
 * Alternative to using @SaktiLock annotation
 */
public interface DistributedLockService {
    /**
     * Execute with distributed lock (default timeout)
     */
    <T> T executeWithLock(String lockKey, CheckedSupplier<T> action) throws Exception;
    
    /**
     * Execute with distributed lock (custom timeout)
     */
    <T> T executeWithLock(String lockKey, long waitTimeMs, long leaseTimeMs, 
                         CheckedSupplier<T> action) throws Exception;
    
    /**
     * Execute with lock + idempotency
     */
    <T> T executeWithLockAndIdempotency(String lockKey, String idempotencyKey,
                                       CheckedSupplier<T> action) throws Exception;
}