package id.go.kemenkeu.djpbn.sakti.core;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import java.time.Duration;

public class IdempotencyManager {
    private final RedissonClient redisson;
    private final String prefix;

    public IdempotencyManager(RedissonClient redisson, String prefix) {
        this.redisson = redisson; this.prefix = prefix == null ? "sakti:idemp:" : prefix;
    }

    public boolean checkExists(String key) {
        RBucket<String> b = redisson.getBucket(prefix + key);
        return b.isExists();
    }

    public void markProcessing(String key, long ttlSeconds) {
        RBucket<String> b = redisson.getBucket(prefix + key);
        b.set("processing:" + System.currentTimeMillis(), Duration.ofSeconds(ttlSeconds));
    }

    public void markCompleted(String key, long ttlSeconds) {
        RBucket<String> b = redisson.getBucket(prefix + key);
        b.set("completed:" + System.currentTimeMillis(), Duration.ofSeconds(ttlSeconds));
    }

    public void rollback(String key) { redisson.getBucket(prefix + key).delete(); }
}
