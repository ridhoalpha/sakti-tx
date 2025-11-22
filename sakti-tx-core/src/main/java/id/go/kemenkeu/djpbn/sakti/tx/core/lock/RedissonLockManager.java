package id.go.kemenkeu.djpbn.sakti.tx.core.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class RedissonLockManager implements LockManager {
    
    private static final Logger log = LoggerFactory.getLogger(RedissonLockManager.class);
    private final RedissonClient redissonClient;
    
    public RedissonLockManager(RedissonClient redissonClient) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        this.redissonClient = redissonClient;
    }
    
    @Override
    public LockHandle tryLock(String lockKey, long waitTimeMs, long leaseTimeMs) throws Exception {
        final RLock lock = redissonClient.getLock(lockKey);
        final boolean acquired = lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
        
        if (!acquired) {
            log.warn("Failed to acquire lock: {}", lockKey);
        } else {
            log.debug("Lock acquired: {}", lockKey);
        }
        
        return new LockHandle() {
            @Override
            public boolean isAcquired() {
                return acquired;
            }
            
            @Override
            public void release() {
                if (acquired && lock.isLocked() && lock.isHeldByCurrentThread()) {
                    try {
                        lock.unlock();
                        log.debug("Lock released: {}", lockKey);
                    } catch (Exception e) {
                        log.error("Failed to release lock: {}", lockKey, e);
                    }
                }
            }
        };
    }
}