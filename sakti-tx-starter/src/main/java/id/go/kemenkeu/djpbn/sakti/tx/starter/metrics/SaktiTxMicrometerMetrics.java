package id.go.kemenkeu.djpbn.sakti.tx.starter.metrics;

import id.go.kemenkeu.djpbn.sakti.tx.core.metrics.TransactionMetrics;
import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

/**
 * Micrometer bridge for SAKTI TX metrics
 * Exposes metrics to Prometheus/Grafana
 * 
 * Metrics exposed:
 * - sakti_tx_total{status}              - Total transactions
 * - sakti_tx_duration_seconds           - Transaction duration
 * - sakti_tx_operations_total           - Total operations tracked
 * - sakti_tx_compensation_total{result} - Compensation attempts
 * - sakti_tx_risk_flags_total{risk}     - Risk flags encountered
 * 
 * @version 1.0.0
 */
@Component
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class SaktiTxMicrometerMetrics {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxMicrometerMetrics.class);
    
    private final MeterRegistry registry;
    private final TransactionMetrics txMetrics;
    
    public SaktiTxMicrometerMetrics(MeterRegistry registry, TransactionMetrics txMetrics) {
        this.registry = registry;
        this.txMetrics = txMetrics;
    }
    
    @PostConstruct
    public void init() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Registering SAKTI TX metrics to Micrometer/Prometheus...");
        log.info("═══════════════════════════════════════════════════════════");
        
        // ═══════════════════════════════════════════════════════════════
        // 1. TRANSACTION TOTALS (by status)
        // ═══════════════════════════════════════════════════════════════
        
        Gauge.builder("sakti_tx_total", txMetrics, TransactionMetrics::getTotalTransactions)
            .description("Total transactions processed")
            .tag("status", "all")
            .register(registry);
        
        Gauge.builder("sakti_tx_total", txMetrics, TransactionMetrics::getCommittedTransactions)
            .description("Total committed transactions")
            .tag("status", "committed")
            .register(registry);
        
        Gauge.builder("sakti_tx_total", txMetrics, TransactionMetrics::getRolledBackTransactions)
            .description("Total rolled back transactions")
            .tag("status", "rolled_back")
            .register(registry);
        
        Gauge.builder("sakti_tx_total", txMetrics, TransactionMetrics::getFailedTransactions)
            .description("Total failed transactions")
            .tag("status", "failed")
            .register(registry);
        
        // ═══════════════════════════════════════════════════════════════
        // 2. SUCCESS RATE
        // ═══════════════════════════════════════════════════════════════
        
        Gauge.builder("sakti_tx_success_rate", txMetrics, TransactionMetrics::getSuccessRate)
            .description("Transaction success rate (percentage)")
            .register(registry);
        
        // ═══════════════════════════════════════════════════════════════
        // 3. DURATION METRICS
        // ═══════════════════════════════════════════════════════════════
        
        Gauge.builder("sakti_tx_duration_avg_ms", txMetrics, TransactionMetrics::getAverageDurationMs)
            .description("Average transaction duration (milliseconds)")
            .register(registry);
        
        Gauge.builder("sakti_tx_duration_max_ms", txMetrics, TransactionMetrics::getMaxDurationMs)
            .description("Maximum transaction duration (milliseconds)")
            .register(registry);
        
        // ═══════════════════════════════════════════════════════════════
        // 4. COMPENSATION METRICS
        // ═══════════════════════════════════════════════════════════════
        
        Gauge.builder("sakti_tx_compensation_total", txMetrics, 
                TransactionMetrics::getTotalCompensationAttempts)
            .description("Total compensation attempts")
            .tag("result", "all")
            .register(registry);
        
        Gauge.builder("sakti_tx_compensation_total", txMetrics, 
                TransactionMetrics::getSuccessfulCompensations)
            .description("Successful compensations")
            .tag("result", "success")
            .register(registry);
        
        Gauge.builder("sakti_tx_compensation_total", txMetrics, 
                TransactionMetrics::getFailedCompensations)
            .description("Failed compensations")
            .tag("result", "failed")
            .register(registry);
        
        Gauge.builder("sakti_tx_compensation_success_rate", txMetrics, 
                TransactionMetrics::getCompensationSuccessRate)
            .description("Compensation success rate (percentage)")
            .register(registry);
        
        // ═══════════════════════════════════════════════════════════════
        // 5. RISK FLAGS
        // ═══════════════════════════════════════════════════════════════
        
        for (RiskFlag flag : RiskFlag.values()) {
            Gauge.builder("sakti_tx_risk_flags_total", txMetrics, 
                    m -> m.getRiskFlagCount(flag))
                .description("Risk flags encountered")
                .tag("risk", flag.name())
                .tag("level", flag.getLevel().name())
                .register(registry);
        }
        
        log.info("✓ Registered {} SAKTI TX metrics", 
            registry.getMeters().size());
        log.info("  → Prometheus endpoint: /actuator/prometheus");
        log.info("═══════════════════════════════════════════════════════════");
    }
}