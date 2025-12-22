package id.go.kemenkeu.djpbn.sakti.tx.starter.worker;

import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
import id.go.kemenkeu.djpbn.sakti.tx.core.lock.LockManager;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ENHANCED: Recovery worker with distributed lock to prevent race conditions
 * 
 * @version 2.0.0
 */
@Component
@ConditionalOnProperty(prefix = "sakti.tx.multi-db.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionRecoveryWorker {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionRecoveryWorker.class);
    
    private static final String RECOVERY_LOCK_KEY = "sakti:recovery:scan-lock";
    
    private final TransactionLogManager logManager;
    private final CompensatingTransactionExecutor compensator;
    private final SaktiTxProperties properties;
    private LockManager lockManager;
    
    // Metrics
    private final AtomicLong totalRecoveryAttempts = new AtomicLong(0);
    private final AtomicLong successfulRecoveries = new AtomicLong(0);
    private final AtomicLong failedRecoveries = new AtomicLong(0);
    private final AtomicInteger lastScanFoundCount = new AtomicInteger(0);
    private volatile Instant lastScanTime = null;
    
    public TransactionRecoveryWorker(TransactionLogManager logManager,
                                    CompensatingTransactionExecutor compensator,
                                    SaktiTxProperties properties) {
        this.logManager = logManager;
        this.compensator = compensator;
        this.properties = properties;
        this.lockManager = null;
    }
    
    /**
     * Optional: Set LockManager after construction if available
     */
    public void setLockManager(LockManager lockManager) {
        this.lockManager = lockManager;
    }
    
    @PostConstruct
    public void init() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Transaction Recovery Worker INITIALIZED (v2.0 - with distributed lock)");
        log.info("Scan Interval: {}ms", properties.getMultiDb().getRecovery().getScanIntervalMs());
        log.info("Stall Timeout: {}ms", properties.getMultiDb().getRecovery().getStallTimeoutMs());
        log.info("Max Recovery Attempts: {}", properties.getMultiDb().getRecovery().getMaxRecoveryAttempts());
        log.info("Distributed Lock: {}", lockManager != null ? "ENABLED" : "DISABLED");
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    /**
     * ENHANCED: Scheduled scan with distributed lock
     */
    @Scheduled(
        fixedDelayString = "${sakti.tx.multi-db.recovery.scan-interval-ms:60000}",
        initialDelayString = "${sakti.tx.multi-db.recovery.initial-delay-ms:30000}"
    )
    public void scanAndRecoverStalledTransactions() {
        lastScanTime = Instant.now();
        
        // ═══════════════════════════════════════════════════════════════
        // CRITICAL: Acquire distributed lock to prevent race conditions
        // ═══════════════════════════════════════════════════════════════
        
        LockManager.LockHandle lock = null;
        
        try {
            // Try to acquire lock if LockManager available
            if (lockManager != null) {
                lock = lockManager.tryLock(RECOVERY_LOCK_KEY, 100, 60000);
                
                if (!lock.isAcquired()) {
                    log.debug("Recovery scan already in progress by another worker - skipping");
                    return;
                }
                
                log.debug("Acquired recovery lock - starting scan...");
            } else {
                log.debug("LockManager not available - proceeding without distributed lock");
            }
            
            Duration stallTimeout = Duration.ofMillis(
                properties.getMultiDb().getRecovery().getStallTimeoutMs()
            );
            
            List<TransactionLog> stalledTxs = logManager.findStalledTransactions(stallTimeout);
            lastScanFoundCount.set(stalledTxs.size());
            
            if (stalledTxs.isEmpty()) {
                log.debug("Recovery scan completed - no stalled transactions found");
                return;
            }
            
            log.warn("═══════════════════════════════════════════════════════════");
            log.warn("Recovery scan found {} stalled transactions", stalledTxs.size());
            log.warn("═══════════════════════════════════════════════════════════");
            
            for (TransactionLog tx : stalledTxs) {
                recoverTransaction(tx);
            }
            
            log.info("Recovery scan completed - processed {} transactions", stalledTxs.size());
            logMetrics();
            
        } catch (Exception e) {
            log.error("Recovery scan failed", e);
        } finally {
            if (lock != null && lock.isAcquired()) {
                lock.release();
                log.debug("Released recovery lock");
            }
        }
    }
    
    /**
     * Recover single stalled transaction
     */
    private void recoverTransaction(TransactionLog tx) {
        String txId = tx.getTxId();
        MDC.put("txId", txId);
        totalRecoveryAttempts.incrementAndGet();
        
        try {
            log.warn("Attempting recovery for stalled transaction: {}", txId);
            log.warn("   Business Key: {}", tx.getBusinessKey());
            log.warn("   State: {}", tx.getState());
            log.warn("   Operations: {}", tx.getOperations().size());
            log.warn("   Age: {}s", Duration.between(tx.getStartTime(), Instant.now()).getSeconds());
            log.warn("   Retry Count: {}", tx.getRetryCount());
            
            // Check if max recovery attempts exceeded
            int maxAttempts = properties.getMultiDb().getRecovery().getMaxRecoveryAttempts();
            if (tx.getRetryCount() >= maxAttempts) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("Max recovery attempts ({}) exceeded for txId: {}", maxAttempts, txId);
                log.error("Marking as FAILED - manual intervention required");
                log.error("═══════════════════════════════════════════════════════════");
                
                logManager.markFailed(txId, 
                    String.format("Max recovery attempts (%d) exceeded", maxAttempts));
                failedRecoveries.incrementAndGet();
                return;
            }
            
            // Determine recovery action based on state
            switch (tx.getState()) {
                case STARTED:
                case IN_PROGRESS:
                    log.warn("Transaction {} stuck in {} state - forcing rollback", 
                        txId, tx.getState());
                    forceRollback(tx);
                    break;
                    
                case ROLLING_BACK:
                    log.warn("Transaction {} stuck in ROLLING_BACK - retrying rollback", txId);
                    retryRollback(tx);
                    break;
                    
                case COMMITTING:
                    log.error("Transaction {} stuck in COMMITTING state - POTENTIAL PARTIAL COMMIT", txId);
                    log.error("Manual verification required - cannot auto-recover");
                    logManager.markFailed(txId, "Stuck in COMMITTING state - manual verification required");
                    failedRecoveries.incrementAndGet();
                    break;
                    
                default:
                    log.warn("Transaction {} in unexpected state: {}", txId, tx.getState());
                    break;
            }
            
        } catch (Exception e) {
            log.error("Recovery failed for transaction: {}", txId, e);
            failedRecoveries.incrementAndGet();
            
        } finally {
            MDC.remove("txId");
        }
    }
    
    private void forceRollback(TransactionLog tx) {
        String txId = tx.getTxId();
        
        try {
            if (tx.getOperations().isEmpty()) {
                log.info("No operations to rollback for txId: {} - marking as rolled back", txId);
                logManager.markRolledBack(txId);
                successfulRecoveries.incrementAndGet();
                return;
            }
            
            logManager.markRollingBack(txId, "Recovery worker: forced rollback");
            tx.incrementRetry();
            logManager.saveLog(tx);
            
            compensator.rollback(tx);
            logManager.markRolledBack(txId);
            
            log.info("✓ Recovery successful - transaction {} rolled back", txId);
            successfulRecoveries.incrementAndGet();
            
        } catch (Exception e) {
            log.error("✗ Recovery rollback failed for txId: {}", txId, e);
            logManager.markFailed(txId, "Recovery rollback failed: " + e.getMessage());
            failedRecoveries.incrementAndGet();
        }
    }
    
    private void retryRollback(TransactionLog tx) {
        String txId = tx.getTxId();
        
        try {
            tx.incrementRetry();
            logManager.saveLog(tx);
            
            compensator.rollback(tx);
            logManager.markRolledBack(txId);
            
            log.info("✓ Recovery retry successful - transaction {} rolled back", txId);
            successfulRecoveries.incrementAndGet();
            
        } catch (Exception e) {
            log.error("✗ Recovery retry failed for txId: {}", txId, e);
            
            int maxAttempts = properties.getMultiDb().getRecovery().getMaxRecoveryAttempts();
            if (tx.getRetryCount() >= maxAttempts) {
                logManager.markFailed(txId, 
                    String.format("Recovery retry failed after %d attempts: %s", 
                        maxAttempts, e.getMessage()));
            }
            failedRecoveries.incrementAndGet();
        }
    }
    
    private void logMetrics() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Recovery Worker Metrics:");
        log.info("   Total Attempts: {}", totalRecoveryAttempts.get());
        log.info("   Successful: {}", successfulRecoveries.get());
        log.info("   Failed: {}", failedRecoveries.get());
        log.info("   Success Rate: {:.2f}%", 
            totalRecoveryAttempts.get() > 0 
                ? (successfulRecoveries.get() * 100.0 / totalRecoveryAttempts.get()) 
                : 0.0);
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    /**
     * Get metrics for monitoring
     */
    public RecoveryMetrics getMetrics() {
        return new RecoveryMetrics(
            totalRecoveryAttempts.get(),
            successfulRecoveries.get(),
            failedRecoveries.get(),
            lastScanFoundCount.get(),
            lastScanTime
        );
    }
    
    /**
     * Metrics DTO
     */
    public static class RecoveryMetrics {
        public final long totalAttempts;
        public final long successful;
        public final long failed;
        public final int lastScanFound;
        public final Instant lastScanTime;
        
        public RecoveryMetrics(long totalAttempts, long successful, long failed,
                              int lastScanFound, Instant lastScanTime) {
            this.totalAttempts = totalAttempts;
            this.successful = successful;
            this.failed = failed;
            this.lastScanFound = lastScanFound;
            this.lastScanTime = lastScanTime;
        }
        
        public double getSuccessRate() {
            return totalAttempts > 0 ? (successful * 100.0 / totalAttempts) : 0.0;
        }
    }
}