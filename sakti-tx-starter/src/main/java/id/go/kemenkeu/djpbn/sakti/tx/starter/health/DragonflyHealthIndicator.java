package id.go.kemenkeu.djpbn.sakti.tx.starter.health;

import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DragonflyHealthIndicator implements HealthIndicator {
    
    private static final Logger log = LoggerFactory.getLogger(DragonflyHealthIndicator.class);
    
    private final RedissonClient redissonClient;
    private final SaktiTxProperties properties;
    
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    
    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
    
    public DragonflyHealthIndicator(RedissonClient redissonClient, 
                                    SaktiTxProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }
    
    @Override
    public Health health() {
        if (!properties.getDragonfly().isEnabled()) {
            return Health.up()
                .withDetail("status", "disabled")
                .build();
        }
        
        try {
            String ping = redissonClient.getNodesGroup().pingAll() ? "PONG" : "FAILED";
            
            if (properties.getCircuitBreaker().isEnabled()) {
                consecutiveFailures.set(0);
                if (circuitState == CircuitState.HALF_OPEN) {
                    circuitState = CircuitState.CLOSED;
                    log.info("Circuit breaker CLOSED - Dragonfly recovered");
                }
            }
            
            return Health.up()
                .withDetail("dragonfly", ping)
                .withDetail("circuitState", circuitState)
                .withDetail("url", properties.getDragonfly().getUrl())
                .build();
                
        } catch (Exception e) {
            log.error("Dragonfly health check failed: {}", e.getMessage());
            
            if (properties.getCircuitBreaker().isEnabled()) {
                handleFailure();
            }
            
            return Health.down()
                .withDetail("dragonfly", e.getMessage())
                .withDetail("circuitState", circuitState)
                .withDetail("consecutiveFailures", consecutiveFailures.get())
                .build();
        }
    }
    
    private void handleFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        if (failures >= properties.getCircuitBreaker().getFailureThreshold() 
            && circuitState == CircuitState.CLOSED) {
            circuitState = CircuitState.OPEN;
            log.error("Circuit breaker OPEN - Dragonfly unavailable after {} failures", failures);
        }
    }
    
    public boolean isCircuitOpen() {
        if (!properties.getCircuitBreaker().isEnabled() || !properties.getDragonfly().isEnabled()) {
            return !properties.getDragonfly().isEnabled();
        }
        
        if (circuitState == CircuitState.CLOSED) {
            return false;
        }
        
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        if (timeSinceLastFailure >= properties.getCircuitBreaker().getRecoveryTimeoutMs() 
            && circuitState == CircuitState.OPEN) {
            circuitState = CircuitState.HALF_OPEN;
            log.info("Circuit breaker HALF_OPEN - Testing recovery");
            return false;
        }
        
        return circuitState == CircuitState.OPEN;
    }
    
    public CircuitState getCircuitState() {
        return circuitState;
    }
}