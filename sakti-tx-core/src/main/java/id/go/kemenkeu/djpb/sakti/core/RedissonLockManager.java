package id.go.kemenkeu.djpbn.sakti.core;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;

public class RedissonLockManager implements LockManager {
    private final RedissonClient redisson;
    public RedissonLockManager(RedissonClient redisson) { this.redisson = redisson; }

    @Override
    public LockHandle tryLock(String key, long waitMillis, long leaseMillis) throws Exception {
        final RLock lock = redisson.getLock(key);
        boolean ok = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        return new LockHandle() {
            @Override
            public boolean isAcquired() { return ok; }
            @Override
            public void release() {
                try {
                    if (ok && lock.isLocked() && lock.isHeldByCurrentThread()) lock.unlock();
                } catch (Exception ignored) {}
            }
        };
    }
}
