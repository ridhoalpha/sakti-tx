package id.go.kemenkeu.djpbn.sakti.tx.starter.service;

import java.util.function.Supplier;

/**
 * High-level service for distributed lock operations
 * Alternative to using @SaktiLock annotation
 */
public interface DistributedLockService {
    
    /**
     * Execute with distributed lock (default timeout)
     */
    <T> T executeWithLock(String lockKey, Supplier<T> action) throws Exception;
    
    /**
     * Execute with distributed lock (custom timeout)
     */
    <T> T executeWithLock(String lockKey, long waitTimeMs, long leaseTimeMs, 
                         Supplier<T> action) throws Exception;
    
    /**
     * Execute with lock + idempotency
     */
    <T> T executeWithLockAndIdempotency(String lockKey, String idempotencyKey,
                                       Supplier<T> action) throws Exception;
}