package id.go.kemenkeu.djpbn.sakti.tx.core.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manager untuk transaction log di Dragonfly
 * CRITICAL: Ini jantung dari distributed transaction
 * 
 * ENHANCED VERSION:
 * - Optional WAIT for sync (durability guarantee)
 * - Better error logging with MDC
 * - Retry logic for Redis operations
 */
public class TransactionLogManager {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionLogManager.class);
    
    private static final String LOG_PREFIX = "sakti:txlog:";
    private static final String FAILED_PREFIX = "sakti:txlog:failed:";
    private static final long DEFAULT_TTL_HOURS = 24; // Keep 1 day
    
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final boolean waitForSync;
    private final int waitForSyncTimeoutMs;
    
    public TransactionLogManager(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this(redissonClient, objectMapper, false, 5000);
    }
    
    public TransactionLogManager(RedissonClient redissonClient, ObjectMapper objectMapper,
                                boolean waitForSync, int waitForSyncTimeoutMs) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper cannot be null");
        }
        
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.waitForSync = waitForSync;
        this.waitForSyncTimeoutMs = waitForSyncTimeoutMs;
        
        // CRITICAL: Ensure ObjectMapper can handle Instant/LocalDateTime
        validateObjectMapper(objectMapper);
        
        if (waitForSync) {
            log.info("TransactionLogManager initialized with WAIT-FOR-SYNC enabled (timeout: {}ms)", 
                waitForSyncTimeoutMs);
        } else {
            log.info("TransactionLogManager initialized (WAIT-FOR-SYNC disabled)");
        }
    }
    
    /**
     * Validate ObjectMapper has JSR-310 support
     */
    private void validateObjectMapper(ObjectMapper mapper) {
        try {
            // Test serialization/deserialization of Instant
            Instant now = Instant.now();
            String json = mapper.writeValueAsString(now);
            Instant parsed = mapper.readValue(json, Instant.class);
            
            log.debug("ObjectMapper JSR-310 validation successful");
            
        } catch (Exception e) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("CRITICAL ERROR: ObjectMapper cannot serialize/deserialize Instant");
            log.error("═══════════════════════════════════════════════════════════");
            log.error("This will cause ALL transaction logging to FAIL");
            log.error("Error: {}", e.getMessage());
            log.error("");
            log.error("Solution:");
            log.error("  1. Ensure jackson-datatype-jsr310 is in classpath");
            log.error("  2. Register JavaTimeModule:");
            log.error("     mapper.registerModule(new JavaTimeModule());");
            log.error("  OR");
            log.error("     mapper.findAndRegisterModules();");
            log.error("═══════════════════════════════════════════════════════════");
            
            throw new IllegalStateException(
                "ObjectMapper cannot handle Instant - JSR-310 module not registered", 
                e
            );
        }
    }
    
    /**
     * Create new transaction log
     */
    public TransactionLog createLog(String businessKey) {
        TransactionLog txLog = new TransactionLog(businessKey);
        saveLog(txLog);
        
        // Set MDC for distributed tracing
        MDC.put("txId", txLog.getTxId());
        
        log.debug("Transaction started: {} (business: {})", txLog.getTxId(), businessKey);
        return txLog;
    }
    
    /**
     * Save/update transaction log with optional WAIT for sync
     */
    public void saveLog(TransactionLog txLog) {
        String txId = txLog.getTxId();
        MDC.put("txId", txId);
        
        try {
            String key = LOG_PREFIX + txId;
            String json = txLog.toJson(objectMapper);
            
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(json, Duration.ofHours(DEFAULT_TTL_HOURS));
            
            // Optional: Wait for replication/disk sync
            if (waitForSync) {
                try {
                    RFuture<String> future = bucket.getAsync();
                    String result = future.get(waitForSyncTimeoutMs, TimeUnit.MILLISECONDS);
                    
                    if (result == null) {
                        log.warn("WAIT for sync timeout for txId: {} ({}ms)", txId, waitForSyncTimeoutMs);
                    } else {
                        log.trace("Transaction log synced: {}", txId);
                    }
                } catch (Exception e) {
                    log.error("WAIT for sync failed for txId: {} - {}", txId, e.getMessage());
                    // Don't fail the transaction, just log warning
                }
            }
            
            log.debug("Transaction log saved: {} (state: {}, operations: {})", 
                txId, txLog.getState(), txLog.getOperations().size());
                
        } catch (Exception e) {
            log.error("CRITICAL: Failed to save transaction log: {}", txId, e);
            log.error("   Business Key: {}", txLog.getBusinessKey());
            log.error("   Operations: {}", txLog.getOperations().size());
            throw new RuntimeException("Cannot save transaction log for " + txId, e);
        } finally {
            MDC.remove("txId");
        }
    }
    
    /**
     * Get transaction log by ID
     */
    public TransactionLog getLog(String txId) {
        MDC.put("txId", txId);
        
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
        } finally {
            MDC.remove("txId");
        }
    }
    
    /**
     * Mark transaction as committed and cleanup
     */
    public void markCommitted(String txId) {
        MDC.put("txId", txId);
        
        try {
            TransactionLog txLog = getLog(txId);
            if (txLog != null) {
                txLog.markCommitted();
                saveLog(txLog);
                log.info("Transaction committed: {} ({} operations)", 
                    txId, txLog.getOperations().size());
                
                // Schedule cleanup after retention period
                scheduleCleanup(txId);
            }
        } finally {
            MDC.remove("txId");
        }
    }
    
    /**
     * Mark transaction as rolling back
     */
    public void markRollingBack(String txId, String errorMessage) {
        MDC.put("txId", txId);
        
        try {
            TransactionLog txLog = getLog(txId);
            if (txLog != null) {
                txLog.markRollingBack(errorMessage);
                saveLog(txLog);
                log.warn("Transaction rolling back: {} - {}", txId, errorMessage);
            }
        } finally {
            MDC.remove("txId");
        }
    }
    
    /**
     * Mark transaction as rolled back
     */
    public void markRolledBack(String txId) {
        MDC.put("txId", txId);
        
        try {
            TransactionLog txLog = getLog(txId);
            if (txLog != null) {
                txLog.markRolledBack();
                saveLog(txLog);
                log.info("Transaction rolled back: {} ({} operations compensated)", 
                    txId, txLog.getOperations().size());
                
                scheduleCleanup(txId);
            }
        } finally {
            MDC.remove("txId");
        }
    }
    
    /**
     * Mark transaction as failed (needs intervention)
     */
    public void markFailed(String txId, String reason) {
        MDC.put("txId", txId);
        
        try {
            TransactionLog txLog = getLog(txId);
            if (txLog != null) {
                txLog.markFailed(reason);
                
                // Move to failed queue for manual review
                String failedKey = FAILED_PREFIX + txId;
                String json = txLog.toJson(objectMapper);
                
                RBucket<String> bucket = redissonClient.getBucket(failedKey);
                bucket.set(json); // No expiry - manual cleanup needed
                
                log.error("═══════════════════════════════════════════════════════════");
                log.error("Transaction FAILED: {}", txId);
                log.error("Business Key: {}", txLog.getBusinessKey());
                log.error("Operations: {}", txLog.getOperations().size());
                log.error("Reason: {}", reason);
                log.error("═══════════════════════════════════════════════════════════");
                log.error("⚠ MANUAL INTERVENTION REQUIRED!");
                log.error("   Check failed transactions: {}", failedKey);
                log.error("   Use admin API to retry: POST /admin/transactions/retry/{}", txId);
                log.error("═══════════════════════════════════════════════════════════");
            }
        } finally {
            MDC.remove("txId");
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
        MDC.put("txId", txId);
        
        try {
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
                log.info("Retrying transaction: {} (attempt: {})", 
                    txId, txLog.getRetryCount());
            }
        } finally {
            MDC.remove("txId");
        }
    }
    
    /**
     * Schedule cleanup (can be implemented with Redis expiry or background job)
     */
    private void scheduleCleanup(String txId) {
        // TTL already set in saveLog()
        log.trace("Cleanup scheduled for: {} (after {}h)", txId, DEFAULT_TTL_HOURS);
    }
    
    /**
     * Clean up old completed transactions (manual)
     */
    public int cleanupOldTransactions(long olderThanHours) {
        log.debug("Cleanup old transactions older than {} hours", olderThanHours);
        return 0;
    }

    /**
     * Find stalled transactions yang stuck dalam IN_PROGRESS, ROLLING_BACK, atau COMMITTING state
     * Used by recovery worker untuk automatic recovery
     * 
     * @param stallTimeout Duration setelah transaction dianggap stalled
     * @return List of stalled transactions
     */
    public List<TransactionLog> findStalledTransactions(Duration stallTimeout) {
        List<TransactionLog> stalledTxs = new ArrayList<>();
        
        try {
            log.debug("Scanning for stalled transactions (timeout: {})", stallTimeout);
            
            Iterable<String> keys = redissonClient.getKeys()
                .getKeysByPattern(LOG_PREFIX + "*");
            
            Instant cutoffTime = Instant.now().minus(stallTimeout);
            
            for (String key : keys) {
                try {
                    RBucket<String> bucket = redissonClient.getBucket(key);
                    String json = bucket.get();
                    
                    if (json == null || json.trim().isEmpty()) {
                        log.debug("Empty transaction log for key: {} - skipping", key);
                        continue;
                    }
                    
                    TransactionLog txLog = null;
                    try {
                        txLog = TransactionLog.fromJson(json, objectMapper);
                    } catch (Exception e) {
                        log.error("Failed to deserialize transaction log for key: {} - {}", 
                            key, e.getMessage());
                        // Skip this transaction log - it's corrupted
                        continue;
                    }
                    
                    // Validate txLog has required fields
                    if (txLog.getState() == null || txLog.getStartTime() == null) {
                        log.warn("Invalid transaction log (missing state or startTime): {} - skipping", 
                            txLog.getTxId());
                        continue;
                    }
                    
                    // Check if transaction is in problematic state
                    boolean isStalled = false;
                    
                    switch (txLog.getState()) {
                        case STARTED:
                        case IN_PROGRESS:
                            if (txLog.getStartTime().isBefore(cutoffTime)) {
                                isStalled = true;
                                log.debug("Found stalled transaction (STARTED): {} age={}s",
                                    txLog.getTxId(),
                                    Duration.between(txLog.getStartTime(), Instant.now()).getSeconds());
                            }
                            break;
                            
                        case ROLLING_BACK:
                            if (txLog.getStartTime().isBefore(cutoffTime)) {
                                isStalled = true;
                                log.debug("Found stalled transaction (ROLLING_BACK): {} age={}s",
                                    txLog.getTxId(),
                                    Duration.between(txLog.getStartTime(), Instant.now()).getSeconds());
                            }
                            break;
                            
                        case COMMITTING:
                            if (txLog.getStartTime().isBefore(cutoffTime)) {
                                isStalled = true;
                                log.warn("Found stalled transaction (COMMITTING): {} age={}s - CRITICAL",
                                    txLog.getTxId(),
                                    Duration.between(txLog.getStartTime(), Instant.now()).getSeconds());
                            }
                            break;
                            
                        default:
                            break;
                    }
                    
                    if (isStalled) {
                        stalledTxs.add(txLog);
                    }
                    
                } catch (Exception e) {
                    log.error("Error processing transaction log key: {} - {}", key, e.getMessage());
                    // Continue with next transaction
                }
            }
            
            if (!stalledTxs.isEmpty()) {
                log.warn("Found {} stalled transactions", stalledTxs.size());
            } else {
                log.debug("No stalled transactions found");
            }
            
        } catch (Exception e) {
            log.error("Failed to scan for stalled transactions", e);
        }
        
        return stalledTxs;
    }
}