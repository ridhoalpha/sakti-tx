package id.go.kemenkeu.djpbn.sakti.tx.core.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class CacheManager {
    
    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String prefix;
    
    public CacheManager(RedissonClient redissonClient, ObjectMapper objectMapper, String prefix) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper cannot be null");
        }
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.prefix = (prefix == null || prefix.trim().isEmpty()) ? "sakti:cache:" : prefix;
    }
    
    /**
     * Get cache with Class type (for simple types)
     * Used when returnType is explicitly specified in annotation
     */
    public <T> T get(String key, Class<T> type) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            
            if (bucket.isExists()) {
                String json = bucket.get();
                T value = objectMapper.readValue(json, type);
                log.debug("Cache hit: {}", key);
                return value;
            }
            
            log.debug("Cache miss: {}", key);
            return null;
        } catch (Exception e) {
            log.error("Failed to get cache: {}", key, e);
            return null;
        }
    }
    
    /**
     * Get cache with TypeReference (for generic types like List, Map, etc.)
     * Used for auto-detect from method signature
     */
    public <T> T get(String key, TypeReference<T> typeRef) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            
            if (bucket.isExists()) {
                String json = bucket.get();
                T value = objectMapper.readValue(json, typeRef);
                log.debug("Cache hit: {}", key);
                return value;
            }
            
            log.debug("Cache miss: {}", key);
            return null;
            
        } catch (Exception e) {
            log.error("Failed to get cache: {} - EVICTING corrupt cache", key, e);
            
            // AUTO-EVICT corrupt cache
            try {
                evict(key);
                log.info("Evicted corrupt cache: {}", key);
            } catch (Exception evictError) {
                log.error("Failed to evict corrupt cache: {}", key, evictError);
            }
            
            return null;
        }
    }
    
    /**
     * Store object in cache
     */
    public void put(String key, Object value, long ttlSeconds) {
        if (key == null || key.trim().isEmpty() || value == null) {
            return;
        }
        
        try {
            String fullKey = prefix + key;
            String json = objectMapper.writeValueAsString(value);
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            bucket.set(json, Duration.ofSeconds(ttlSeconds));
            log.debug("Cache stored: {} (TTL: {}s)", key, ttlSeconds);
        } catch (Exception e) {
            log.error("Failed to put cache: {}", key, e);
        }
    }
    
    /**
     * Evict cache entry
     */
    public void evict(String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            boolean deleted = bucket.delete();
            if (deleted) {
                log.debug("Cache evicted: {}", key);
            }
        } catch (Exception e) {
            log.error("Failed to evict cache: {}", key, e);
        }
    }
    
    /**
     * Clear all cache entries with given prefix
     */
    public void clear(String keyPrefix) {
        try {
            String pattern = prefix + keyPrefix + "*";
            redissonClient.getKeys().deleteByPattern(pattern);
            log.info("Cache cleared for pattern: {}", pattern);
        } catch (Exception e) {
            log.error("Failed to clear cache with prefix: {}", keyPrefix, e);
        }
    }
    
    /**
     * Check if cache key exists
     */
    public boolean exists(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        
        try {
            String fullKey = prefix + key;
            RBucket<String> bucket = redissonClient.getBucket(fullKey);
            return bucket.isExists();
        } catch (Exception e) {
            log.error("Failed to check cache existence: {}", key, e);
            return false;
        }
    }
}