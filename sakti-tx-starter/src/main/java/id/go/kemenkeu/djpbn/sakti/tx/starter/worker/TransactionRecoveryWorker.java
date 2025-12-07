package id.go.kemenkeu.djpbn.sakti.tx.starter.worker;

import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
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
 * Background worker untuk recovery stuck/failed transactions
 * 
 * FEATURES:
 * - Scheduled scanning untuk IN_PROGRESS/ROLLING_BACK transactions
 * - Automatic completion untuk stalled transactions
 * - Metrics untuk monitoring
 * - Configurable scan interval dan timeout
 * 
 * SAFETY:
 * - Only processes transactions older than configured timeout
 * - Logs all recovery attempts
 * - Marks failed recoveries for manual intervention
 */
@Component
@ConditionalOnProperty(prefix = "sakti.tx.multi-db.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionRecoveryWorker {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionRecoveryWorker.class);
    
    private final TransactionLogManager logManager;
    private final CompensatingTransactionExecutor compensator;
    private final SaktiTxProperties properties;
    
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
    }
    
    @PostConstruct
    public void init() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Transaction Recovery Worker INITIALIZED");
        log.info("Scan Interval: {}ms", properties.getMultiDb().getRecovery().getScanIntervalMs());
        log.info("Stall Timeout: {}ms", properties.getMultiDb().getRecovery().getStallTimeoutMs());
        log.info("Max Recovery Attempts: {}", properties.getMultiDb().getRecovery().getMaxRecoveryAttempts());
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    /**
     * Scheduled task untuk scan stalled transactions
     * Default: setiap 1 menit
     */
    @Scheduled(
        fixedDelayString = "${sakti.tx.multi-db.recovery.scan-interval-ms:60000}",
        initialDelayString = "${sakti.tx.multi-db.recovery.initial-delay-ms:30000}"
    )
    public void scanAndRecoverStalledTransactions() {
        lastScanTime = Instant.now();
        
        try {
            log.debug("Starting recovery scan...");
            
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
                    // Transaction started but never completed - assume it failed
                    log.warn("Transaction {} stuck in {} state - forcing rollback", 
                        txId, tx.getState());
                    forceRollback(tx);
                    break;
                    
                case ROLLING_BACK:
                    // Rollback was started but not completed - retry rollback
                    log.warn("Transaction {} stuck in ROLLING_BACK - retrying rollback", txId);
                    retryRollback(tx);
                    break;
                    
                case COMMITTING:
                    // Commit in progress - this is problematic
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
    
    /**
     * Force rollback untuk transaction yang stuck di STARTED state
     */
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
    
    /**
     * Retry rollback untuk transaction yang stuck di ROLLING_BACK state
     */
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
    
    /**
     * Log current metrics
     */
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