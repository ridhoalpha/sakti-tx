package id.go.kemenkeu.djpbn.sakti.tx.core.context;

import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * Serializable snapshot for async context propagation
 * Captures essential context data for restoration in new thread
 */
public class SaktiTxContextSnapshot implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private final String txId;
    private final String businessKey;
    private final TransactionPhase phase;
    private final Instant startTime;
    private final List<String> resourceNames;
    private final Map<RiskFlag, Integer> riskMetrics;
    private final Set<String> acquiredLocks;
    private final Map<String, Object> metadata;
    private final Instant capturedAt;
    private final long capturedThreadId;
    
    private SaktiTxContextSnapshot(Builder builder) {
        this.txId = builder.txId;
        this.businessKey = builder.businessKey;
        this.phase = builder.phase;
        this.startTime = builder.startTime;
        this.resourceNames = Collections.unmodifiableList(builder.resourceNames);
        this.riskMetrics = Collections.unmodifiableMap(builder.riskMetrics);
        this.acquiredLocks = Collections.unmodifiableSet(builder.acquiredLocks);
        this.metadata = Collections.unmodifiableMap(builder.metadata);
        this.capturedAt = builder.capturedAt;
        this.capturedThreadId = builder.capturedThreadId;
    }
    
    /**
     * Capture snapshot from current context
     */
    public static SaktiTxContextSnapshot capture() {
        SaktiTxContext context = SaktiTxContextHolder.get();
        
        if (context == null) {
            throw new IllegalStateException(
                "No transaction context in current thread - cannot capture snapshot");
        }
        
        return new Builder()
            .txId(context.getTxId())
            .businessKey(context.getBusinessKey())
            .phase(context.getPhase())
            .startTime(context.getStartTime())
            .resourceNames(context.getResources().stream()
                .map(ResourceEnlistment::getName)
                .toList())
            .riskMetrics(new HashMap<>(context.getRiskMetrics()))
            .acquiredLocks(new HashSet<>(context.getAcquiredLocks()))
            .metadata(new HashMap<>(context.getMetadata()))
            .capturedAt(Instant.now())
            .capturedThreadId(Thread.currentThread().getId())
            .build();
    }
    
    /**
     * Restore context from snapshot (creates new SaktiTxContext)
     */
    public SaktiTxContext restore() {
        return new SaktiTxContext.Builder()
            .txId(txId)
            .businessKey(businessKey)
            .phase(phase)
            .startTime(startTime)
            .riskMetrics(new HashMap<>(riskMetrics))
            .acquiredLocks(new HashSet<>(acquiredLocks))
            .metadata(new HashMap<>(metadata))
            .build();
    }
    
    // Getters
    public String getTxId() { return txId; }
    public String getBusinessKey() { return businessKey; }
    public TransactionPhase getPhase() { return phase; }
    public Instant getStartTime() { return startTime; }
    public List<String> getResourceNames() { return resourceNames; }
    public Map<RiskFlag, Integer> getRiskMetrics() { return riskMetrics; }
    public Set<String> getAcquiredLocks() { return acquiredLocks; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCapturedAt() { return capturedAt; }
    public long getCapturedThreadId() { return capturedThreadId; }
    
    @Override
    public String toString() {
        return "SaktiTxContextSnapshot{" +
                "txId='" + txId + '\'' +
                ", phase=" + phase +
                ", capturedAt=" + capturedAt +
                ", capturedThread=" + capturedThreadId +
                '}';
    }
    
    // Builder
    public static class Builder {
        private String txId;
        private String businessKey;
        private TransactionPhase phase;
        private Instant startTime;
        private List<String> resourceNames = new ArrayList<>();
        private Map<RiskFlag, Integer> riskMetrics = new HashMap<>();
        private Set<String> acquiredLocks = new HashSet<>();
        private Map<String, Object> metadata = new HashMap<>();
        private Instant capturedAt;
        private long capturedThreadId;
        
        public Builder txId(String txId) {
            this.txId = txId;
            return this;
        }
        
        public Builder businessKey(String businessKey) {
            this.businessKey = businessKey;
            return this;
        }
        
        public Builder phase(TransactionPhase phase) {
            this.phase = phase;
            return this;
        }
        
        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }
        
        public Builder resourceNames(List<String> resourceNames) {
            this.resourceNames = resourceNames;
            return this;
        }
        
        public Builder riskMetrics(Map<RiskFlag, Integer> riskMetrics) {
            this.riskMetrics = riskMetrics;
            return this;
        }
        
        public Builder acquiredLocks(Set<String> acquiredLocks) {
            this.acquiredLocks = acquiredLocks;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public Builder capturedAt(Instant capturedAt) {
            this.capturedAt = capturedAt;
            return this;
        }
        
        public Builder capturedThreadId(long capturedThreadId) {
            this.capturedThreadId = capturedThreadId;
            return this;
        }
        
        public SaktiTxContextSnapshot build() {
            return new SaktiTxContextSnapshot(this);
        }
    }
}