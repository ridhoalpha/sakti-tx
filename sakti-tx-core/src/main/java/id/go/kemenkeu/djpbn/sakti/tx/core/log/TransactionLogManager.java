package id.go.kemenkeu.djpbn.sakti.tx.core.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manager untuk transaction log di Dragonfly
 * CRITICAL: Ini jantung dari distributed transaction
 */
public class TransactionLogManager {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionLogManager.class);
    
    private static final String LOG_PREFIX = "sakti:txlog:";
    private static final String FAILED_PREFIX = "sakti:txlog:failed:";
    private static final long DEFAULT_TTL_HOURS = 24; // Keep 1 days
    
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    
    public TransactionLogManager(RedissonClient redissonClient, ObjectMapper objectMapper) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper cannot be null");
        }
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Create new transaction log
     */
    public TransactionLog createLog(String businessKey) {
        TransactionLog txLog = new TransactionLog(businessKey);
        saveLog(txLog);
        log.debug("Transaction started: {} (business: {})", txLog.getTxId(), businessKey);
        return txLog;
    }
    
    /**
     * Save/update transaction log
     */
    public void saveLog(TransactionLog txLog) {
        try {
            String key = LOG_PREFIX + txLog.getTxId();
            String json = txLog.toJson(objectMapper);
            
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(json, Duration.ofHours(DEFAULT_TTL_HOURS));
            
            log.debug("Transaction log saved: {} (state: {})", 
                txLog.getTxId(), txLog.getState());
                
        } catch (Exception e) {
            log.error("CRITICAL: Failed to save transaction log: {}", txLog.getTxId(), e);
            throw new RuntimeException("Cannot save transaction log", e);
        }
    }
    
    /**
     * Get transaction log by ID
     */
    public TransactionLog getLog(String txId) {
        try {
            String key = LOG_PREFIX + txId;
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            
            if (json == null) {
                log.warn("Transaction log not found: {}", txId);
                return null;
            }
            
            return TransactionLog.fromJson(json, objectMapper);
            
        } catch (Exception e) {
            log.error("Failed to get transaction log: {}", txId, e);
            return null;
        }
    }
    
    /**
     * Mark transaction as committed and cleanup
     */
    public void markCommitted(String txId) {
        TransactionLog txLog = getLog(txId);
        if (txLog != null) {
            txLog.markCommitted();
            saveLog(txLog);
            log.debug("Transaction committed: {}", txId);
            
            // Schedule cleanup after retention period
            scheduleCleanup(txId);
        }
    }
    
    /**
     * Mark transaction as rolling back
     */
    public void markRollingBack(String txId, String errorMessage) {
        TransactionLog txLog = getLog(txId);
        if (txLog != null) {
            txLog.markRollingBack(errorMessage);
            saveLog(txLog);
            log.warn("Transaction rolling back: {} - {}", txId, errorMessage);
        }
    }
    
    /**
     * Mark transaction as rolled back
     */
    public void markRolledBack(String txId) {
        TransactionLog txLog = getLog(txId);
        if (txLog != null) {
            txLog.markRolledBack();
            saveLog(txLog);
            log.debug("Transaction rolled back: {}", txId);
            
            scheduleCleanup(txId);
        }
    }
    
    /**
     * Mark transaction as failed (needs intervention)
     */
    public void markFailed(String txId, String reason) {
        TransactionLog txLog = getLog(txId);
        if (txLog != null) {
            txLog.markFailed(reason);
            
            // Move to failed queue for manual review
            String failedKey = FAILED_PREFIX + txId;
            String json = txLog.toJson(objectMapper);
            
            RBucket<String> bucket = redissonClient.getBucket(failedKey);
            bucket.set(json); // No expiry - manual cleanup needed
            
            log.error("Transaction FAILED: {} - {}", txId, reason);
            log.error("   → Manual intervention required!");
            log.error("   → Check failed transactions: {}", failedKey);
        }
    }
    
    /**
     * Get all failed transactions (for monitoring)
     */
    public List<TransactionLog> getFailedTransactions() {
        List<TransactionLog> failedTxs = new ArrayList<>();
        
        try {
            Iterable<String> keys = redissonClient.getKeys()
                .getKeysByPattern(FAILED_PREFIX + "*");
            
            for (String key : keys) {
                RBucket<String> bucket = redissonClient.getBucket(key);
                String json = bucket.get();
                if (json != null) {
                    failedTxs.add(TransactionLog.fromJson(json, objectMapper));
                }
            }
            
            log.debug("Found {} failed transactions", failedTxs.size());
            
        } catch (Exception e) {
            log.error("Failed to get failed transactions", e);
        }
        
        return failedTxs;
    }
    
    /**
     * Retry failed transaction
     */
    public void retryFailedTransaction(String txId) {
        TransactionLog txLog = getLog(txId);
        if (txLog == null) {
            // Check in failed queue
            String failedKey = FAILED_PREFIX + txId;
            RBucket<String> bucket = redissonClient.getBucket(failedKey);
            String json = bucket.get();
            if (json != null) {
                txLog = TransactionLog.fromJson(json, objectMapper);
            }
        }
        
        if (txLog != null) {
            txLog.incrementRetry();
            txLog.setState(TransactionLog.TransactionState.ROLLING_BACK);
            saveLog(txLog);
            log.debug("Retrying transaction: {} (attempt: {})", 
                txId, txLog.getRetryCount());
        }
    }
    
    /**
     * Schedule cleanup (can be implemented with Redis expiry or background job)
     */
    private void scheduleCleanup(String txId) {
        // TTL already set in saveLog()
        log.debug("Cleanup scheduled for: {} (after {}h)", txId, DEFAULT_TTL_HOURS);
    }
    
    /**
     * Clean up old completed transactions (manual)
     */
    public int cleanupOldTransactions(long olderThanHours) {
        // Implementation for manual cleanup if needed
        log.debug("Cleanup old transactions older than {} hours", olderThanHours);
        return 0;
    }
}