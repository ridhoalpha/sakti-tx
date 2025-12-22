package id.go.kemenkeu.djpbn.sakti.tx.core.compensate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit breaker to prevent compensation storms
 * Stops retry attempts when threshold exceeded
 */
public class CompensationCircuitBreaker {
    
    private static final Logger log = LoggerFactory.getLogger(CompensationCircuitBreaker.class);
    
    private final int failureThreshold;
    private final Duration recoveryWindow;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    
    public CompensationCircuitBreaker(int failureThreshold, Duration recoveryWindow) {
        this.failureThreshold = failureThreshold;
        this.recoveryWindow = recoveryWindow;
    }
    
    /**
     * Check if compensation should be attempted
     */
    public boolean allowCompensation(String txId) {
        CircuitState state = circuits.computeIfAbsent(txId, k -> new CircuitState());
        
        // Check if circuit is open
        if (state.isOpen()) {
            // Check if recovery window passed
            if (state.shouldAttemptRecovery(recoveryWindow)) {
                log.info("Circuit HALF-OPEN for txId: {} - attempting recovery", txId);
                state.halfOpen();
                return true;
            }
            
            log.warn("Circuit OPEN for txId: {} - blocking compensation attempt", txId);
            return false;
        }
        
        return true;
    }
    
    /**
     * Record successful compensation
     */
    public void recordSuccess(String txId) {
        CircuitState state = circuits.get(txId);
        if (state != null) {
            state.recordSuccess();
            
            if (state.isClosed()) {
                log.info("Circuit CLOSED for txId: {} - recovered", txId);
            }
        }
    }
    
    /**
     * Record failed compensation
     */
    public void recordFailure(String txId) {
        CircuitState state = circuits.computeIfAbsent(txId, k -> new CircuitState());
        state.recordFailure();
        
        if (state.getFailureCount() >= failureThreshold) {
            state.open();
            log.error("═══════════════════════════════════════════════════════════");
            log.error("Circuit OPEN for txId: {}", txId);
            log.error("Failure count: {} (threshold: {})", 
                state.getFailureCount(), failureThreshold);
            log.error("Compensation attempts BLOCKED for {} seconds", 
                recoveryWindow.toSeconds());
            log.error("═══════════════════════════════════════════════════════════");
        }
    }
    
    /**
     * Get circuit state
     */
    public CircuitStateInfo getState(String txId) {
        CircuitState state = circuits.get(txId);
        if (state == null) {
            return new CircuitStateInfo(State.CLOSED, 0, null);
        }
        
        return new CircuitStateInfo(
            state.state,
            state.getFailureCount(),
            state.openedAt
        );
    }
    
    /**
     * Clear circuit (for testing or manual reset)
     */
    public void reset(String txId) {
        circuits.remove(txId);
        log.info("Circuit reset for txId: {}", txId);
    }
    
    private static class CircuitState {
        private volatile State state = State.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
        private volatile Instant openedAt;
        
        public boolean isOpen() {
            return state == State.OPEN;
        }
        
        public boolean isClosed() {
            return state == State.CLOSED;
        }
        
        public void open() {
            this.state = State.OPEN;
            this.openedAt = Instant.now();
        }
        
        public void halfOpen() {
            this.state = State.HALF_OPEN;
        }
        
        public void close() {
            this.state = State.CLOSED;
            this.failureCount.set(0);
            this.consecutiveSuccesses.set(0);
            this.openedAt = null;
        }
        
        public void recordSuccess() {
            consecutiveSuccesses.incrementAndGet();
            
            // If in HALF_OPEN and success, close circuit
            if (state == State.HALF_OPEN && consecutiveSuccesses.get() >= 1) {
                close();
            }
        }
        
        public void recordFailure() {
            failureCount.incrementAndGet();
            consecutiveSuccesses.set(0);
        }
        
        public int getFailureCount() {
            return failureCount.get();
        }
        
        public boolean shouldAttemptRecovery(Duration recoveryWindow) {
            if (openedAt == null) {
                return true;
            }
            
            Duration elapsed = Duration.between(openedAt, Instant.now());
            return elapsed.compareTo(recoveryWindow) >= 0;
        }
    }
    
    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Circuit broken, block attempts
        HALF_OPEN  // Testing recovery
    }
    
    public static class CircuitStateInfo {
        private final State state;
        private final int failureCount;
        private final Instant openedAt;
        
        public CircuitStateInfo(State state, int failureCount, Instant openedAt) {
            this.state = state;
            this.failureCount = failureCount;
            this.openedAt = openedAt;
        }
        
        public State getState() { return state; }
        public int getFailureCount() { return failureCount; }
        public Instant getOpenedAt() { return openedAt; }
        
        @Override
        public String toString() {
            return String.format("CircuitState{state=%s, failures=%d, openedAt=%s}",
                state, failureCount, openedAt);
        }
    }
}