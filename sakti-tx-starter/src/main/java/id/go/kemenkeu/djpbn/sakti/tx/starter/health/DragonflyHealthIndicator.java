package id.go.kemenkeu.djpbn.sakti.tx.starter.health;

import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Health indicator with circuit breaker pattern for Dragonfly/Redis
 * Provides graceful degradation when Redis is unavailable
 */
public class DragonflyHealthIndicator implements HealthIndicator {
    
    private static final Logger log = LoggerFactory.getLogger(DragonflyHealthIndicator.class);
    
    private final RedissonClient redissonClient;
    private final SaktiTxProperties properties;
    
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    
    public enum CircuitState {
        CLOSED,   // Normal operation
        OPEN,     // Circuit broken, failing fast
        HALF_OPEN // Testing if service recovered
    }
    
    public DragonflyHealthIndicator(RedissonClient redissonClient, 
                                    SaktiTxProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        log.info("DragonflyHealthIndicator initialized (circuit-breaker: {})", 
            properties.getCircuitBreaker().isEnabled());
    }
    
    @Override
    public Health health() {
        // If Dragonfly not enabled, return UP with disabled status
        if (!properties.getDragonfly().isEnabled()) {
            return Health.up()
                .withDetail("status", "disabled")
                .withDetail("message", "Dragonfly integration is disabled")
                .build();
        }
        
        // If RedissonClient is null (shouldn't happen, but defensive)
        if (redissonClient == null) {
            return Health.down()
                .withDetail("status", "error")
                .withDetail("message", "RedissonClient is null")
                .build();
        }
        
        try {
            // Ping Dragonfly
            boolean pingResult = redissonClient.getNodesGroup().pingAll();
            String ping = pingResult ? "PONG" : "FAILED";
            
            // Reset circuit breaker on success
            if (properties.getCircuitBreaker().isEnabled()) {
                consecutiveFailures.set(0);
                if (circuitState == CircuitState.HALF_OPEN) {
                    circuitState = CircuitState.CLOSED;
                    log.info("✅ Circuit breaker CLOSED - Dragonfly recovered");
                }
            }
            
            return Health.up()
                .withDetail("dragonfly", ping)
                .withDetail("circuitState", circuitState.name())
                .withDetail("url", maskPassword(properties.getDragonfly().getUrl()))
                .withDetail("consecutiveFailures", consecutiveFailures.get())
                .build();
                
        } catch (Exception e) {
            log.error("❌ Dragonfly health check failed: {}", e.getMessage());
            
            // Handle circuit breaker
            if (properties.getCircuitBreaker().isEnabled()) {
                handleFailure();
            }
            
            return Health.down()
                .withDetail("dragonfly", "DOWN")
                .withDetail("error", e.getMessage())
                .withDetail("circuitState", circuitState.name())
                .withDetail("consecutiveFailures", consecutiveFailures.get())
                .withDetail("recommendation", getRecommendation())
                .build();
        }
    }
    
    /**
     * Handle health check failure - update circuit breaker state
     */
    private void handleFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        int threshold = properties.getCircuitBreaker().getFailureThreshold();
        
        if (failures >= threshold && circuitState == CircuitState.CLOSED) {
            circuitState = CircuitState.OPEN;
            log.error("🔴 Circuit breaker OPEN - Dragonfly unavailable after {} failures (threshold: {})", 
                failures, threshold);
            log.error("   → All lock/cache/idempotency operations will be bypassed");
            log.error("   → Will retry after {}ms", properties.getCircuitBreaker().getRecoveryTimeoutMs());
        }
    }
    
    /**
     * Check if circuit breaker is open (should bypass operations)
     */
    public boolean isCircuitOpen() {
        // If circuit breaker disabled, always return false
        if (!properties.getCircuitBreaker().isEnabled()) {
            return false;
        }
        
        // If Dragonfly disabled, consider circuit as "open" (bypass operations)
        if (!properties.getDragonfly().isEnabled()) {
            return true;
        }
        
        // If circuit is CLOSED, allow operations
        if (circuitState == CircuitState.CLOSED) {
            return false;
        }
        
        // If circuit is OPEN, check if recovery timeout passed
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        long recoveryTimeout = properties.getCircuitBreaker().getRecoveryTimeoutMs();
        
        if (timeSinceLastFailure >= recoveryTimeout && circuitState == CircuitState.OPEN) {
            circuitState = CircuitState.HALF_OPEN;
            log.info("🟡 Circuit breaker HALF_OPEN - Testing Dragonfly recovery...");
            return false; // Allow test request
        }
        
        return circuitState == CircuitState.OPEN;
    }
    
    /**
     * Get current circuit breaker state
     */
    public CircuitState getCircuitState() {
        return circuitState;
    }
    
    /**
     * Get consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
    
    /**
     * Mask password in URL for logging
     */
    private String maskPassword(String url) {
        if (url == null || !url.contains("@")) {
            return url;
        }
        // redis://:password@host:port → redis://:****@host:port
        return url.replaceAll("://:[^@]+@", "://****@");
    }
    
    /**
     * Get recommendation message based on circuit state
     */
    private String getRecommendation() {
        if (circuitState == CircuitState.OPEN) {
            return "Circuit breaker is OPEN. Check Dragonfly connectivity: " + 
                   maskPassword(properties.getDragonfly().getUrl());
        }
        return "Dragonfly health check failed. Verify connection settings and network connectivity.";
    }
    
    /**
     * Manually reset circuit breaker (for testing/admin purposes)
     */
    public void resetCircuitBreaker() {
        consecutiveFailures.set(0);
        circuitState = CircuitState.CLOSED;
        log.info("✅ Circuit breaker manually reset to CLOSED");
    }
}