package id.go.kemenkeu.djpbn.sakti.tx.core.idempotency;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class IdempotencyManager {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotencyManager.class);
    private final RedissonClient redissonClient;
    private final String prefix;
    
    public IdempotencyManager(RedissonClient redissonClient, String prefix) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        this.redissonClient = redissonClient;
        this.prefix = (prefix == null || prefix.trim().isEmpty()) ? "sakti:idemp:" : prefix;
    }
    
    public boolean exists(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            return bucket.isExists();
        } catch (Exception e) {
            log.error("Failed to check idempotency key: {}", key, e);
            return false;
        }
    }
    
    public void markProcessing(String key, long ttlSeconds) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        
        try {
            String fullKey = prefix + key;
            String value = "processing:" + System.currentTimeMillis();
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            bucket.set(value, Duration.ofSeconds(ttlSeconds));
            log.debug("Idempotency marked as processing: {}", key);
        } catch (Exception e) {
            log.error("Failed to mark idempotency processing: {}", key, e);
        }
    }
    
    public void markCompleted(String key, long ttlSeconds) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        
        try {
            String fullKey = prefix + key;
            String value = "completed:" + System.currentTimeMillis();
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            bucket.set(value, Duration.ofSeconds(ttlSeconds));
            log.debug("Idempotency marked as completed: {}", key);
        } catch (Exception e) {
            log.error("Failed to mark idempotency completed: {}", key, e);
        }
    }
    
    public void rollback(String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            boolean deleted = bucket.delete();
            if (deleted) {
                log.info("Idempotency rolled back: {}", key);
            }
        } catch (Exception e) {
            log.error("Failed to rollback idempotency: {}", key, e);
        }
    }
    
    public String getValue(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            return bucket.get();
        } catch (Exception e) {
            log.error("Failed to get idempotency value: {}", key, e);
            return null;
        }
    }
}