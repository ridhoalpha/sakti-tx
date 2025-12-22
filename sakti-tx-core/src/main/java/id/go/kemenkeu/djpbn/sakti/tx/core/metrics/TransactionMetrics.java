package id.go.kemenkeu.djpbn.sakti.tx.core.metrics;

import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics collector for SAKTI TX
 * Provides observability without external dependencies
 */
public class TransactionMetrics {
    
    private final AtomicLong totalTransactions = new AtomicLong(0);
    private final AtomicLong committedTransactions = new AtomicLong(0);
    private final AtomicLong rolledBackTransactions = new AtomicLong(0);
    private final AtomicLong failedTransactions = new AtomicLong(0);
    
    private final AtomicLong totalCompensationAttempts = new AtomicLong(0);
    private final AtomicLong successfulCompensations = new AtomicLong(0);
    private final AtomicLong failedCompensations = new AtomicLong(0);
    
    private final Map<RiskFlag, AtomicLong> riskFlagCounts = new ConcurrentHashMap<>();
    
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong maxDurationMs = new AtomicLong(0);
    
    public void recordTransactionStart() {
        totalTransactions.incrementAndGet();
    }
    
    public void recordTransactionCommitted(Duration duration) {
        committedTransactions.incrementAndGet();
        updateDuration(duration);
    }
    
    public void recordTransactionRolledBack(Duration duration) {
        rolledBackTransactions.incrementAndGet();
        updateDuration(duration);
    }
    
    public void recordTransactionFailed(Duration duration) {
        failedTransactions.incrementAndGet();
        updateDuration(duration);
    }
    
    public void recordCompensationAttempt() {
        totalCompensationAttempts.incrementAndGet();
    }
    
    public void recordCompensationSuccess() {
        successfulCompensations.incrementAndGet();
    }
    
    public void recordCompensationFailure() {
        failedCompensations.incrementAndGet();
    }
    
    public void recordRiskFlag(RiskFlag flag) {
        riskFlagCounts.computeIfAbsent(flag, k -> new AtomicLong(0))
            .incrementAndGet();
    }
    
    private void updateDuration(Duration duration) {
        long ms = duration.toMillis();
        totalDurationMs.addAndGet(ms);
        
        // Update max
        long currentMax = maxDurationMs.get();
        while (ms > currentMax) {
            if (maxDurationMs.compareAndSet(currentMax, ms)) {
                break;
            }
            currentMax = maxDurationMs.get();
        }
    }
    
    // Getters
    public long getTotalTransactions() { return totalTransactions.get(); }
    public long getCommittedTransactions() { return committedTransactions.get(); }
    public long getRolledBackTransactions() { return rolledBackTransactions.get(); }
    public long getFailedTransactions() { return failedTransactions.get(); }
    
    public long getTotalCompensationAttempts() { return totalCompensationAttempts.get(); }
    public long getSuccessfulCompensations() { return successfulCompensations.get(); }
    public long getFailedCompensations() { return failedCompensations.get(); }
    
    public long getRiskFlagCount(RiskFlag flag) {
        AtomicLong count = riskFlagCounts.get(flag);
        return count != null ? count.get() : 0;
    }
    
    public Map<RiskFlag, AtomicLong> getAllRiskFlagCounts() {
        return new ConcurrentHashMap<>(riskFlagCounts);
    }
    
    public double getAverageDurationMs() {
        long total = totalTransactions.get();
        return total > 0 ? (double) totalDurationMs.get() / total : 0.0;
    }
    
    public long getMaxDurationMs() { return maxDurationMs.get(); }
    
    public double getSuccessRate() {
        long total = totalTransactions.get();
        return total > 0 ? (double) committedTransactions.get() / total * 100 : 0.0;
    }
    
    public double getCompensationSuccessRate() {
        long total = totalCompensationAttempts.get();
        return total > 0 ? (double) successfulCompensations.get() / total * 100 : 0.0;
    }
    
    @Override
    public String toString() {
        return String.format(
            "TransactionMetrics{total=%d, committed=%d, rolledBack=%d, failed=%d, " +
            "avgDuration=%.2fms, successRate=%.2f%%, compensations=%d (%.2f%% success)}",
            totalTransactions.get(),
            committedTransactions.get(),
            rolledBackTransactions.get(),
            failedTransactions.get(),
            getAverageDurationMs(),
            getSuccessRate(),
            totalCompensationAttempts.get(),
            getCompensationSuccessRate()
        );
    }
}