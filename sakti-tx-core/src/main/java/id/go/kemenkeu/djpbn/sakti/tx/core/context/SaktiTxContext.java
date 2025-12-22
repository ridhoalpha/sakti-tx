package id.go.kemenkeu.djpbn.sakti.tx.core.context;

import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag;
import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskLevel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe, immutable transaction context
 * Separated from ThreadLocal to support async operations
 */
public class SaktiTxContext {
    
    private final String txId;
    private final String businessKey;
    private final TransactionPhase phase;
    private final Instant startTime;
    private final List<ResourceEnlistment> resources;
    private final Map<RiskFlag, Integer> riskMetrics;
    private final Set<String> acquiredLocks;
    private final Map<String, Object> metadata;
    private final ContextPropagationMode propagationMode;
    
    // Private constructor - use Builder
    private SaktiTxContext(Builder builder) {
        this.txId = builder.txId;
        this.businessKey = builder.businessKey;
        this.phase = builder.phase;
        this.startTime = builder.startTime;
        this.resources = Collections.unmodifiableList(new ArrayList<>(builder.resources));
        this.riskMetrics = Collections.unmodifiableMap(new HashMap<>(builder.riskMetrics));
        this.acquiredLocks = Collections.unmodifiableSet(new HashSet<>(builder.acquiredLocks));
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.propagationMode = builder.propagationMode;
    }
    
    // Getters
    public String getTxId() { return txId; }
    public String getBusinessKey() { return businessKey; }
    public TransactionPhase getPhase() { return phase; }
    public Instant getStartTime() { return startTime; }
    public List<ResourceEnlistment> getResources() { return resources; }
    public Map<RiskFlag, Integer> getRiskMetrics() { return riskMetrics; }
    public Set<String> getAcquiredLocks() { return acquiredLocks; }
    public Map<String, Object> getMetadata() { return metadata; }
    public ContextPropagationMode getPropagationMode() { return propagationMode; }
    
    public boolean isActive() {
        return phase.isActive();
    }
    
    public Duration getDuration() {
        return Duration.between(startTime, Instant.now());
    }
    
    public RiskLevel getOverallRiskLevel() {
        if (riskMetrics.isEmpty()) {
            return RiskLevel.LOW;
        }
        
        int totalScore = riskMetrics.entrySet().stream()
            .mapToInt(entry -> entry.getKey().getLevel().getScore() * entry.getValue())
            .sum();
        
        if (totalScore >= 50) return RiskLevel.CRITICAL;
        if (totalScore >= 20) return RiskLevel.HIGH;
        if (totalScore >= 10) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
    
    public boolean hasRiskFlag(RiskFlag flag) {
        return riskMetrics.containsKey(flag) && riskMetrics.get(flag) > 0;
    }
    
    public int getRiskCount(RiskFlag flag) {
        return riskMetrics.getOrDefault(flag, 0);
    }
    
    // Immutable updates - return new instance
    public SaktiTxContext withPhase(TransactionPhase newPhase) {
        if (!phase.canTransitionTo(newPhase)) {
            throw new IllegalStateException(
                "Invalid phase transition: " + phase + " -> " + newPhase);
        }
        return new Builder(this).phase(newPhase).build();
    }
    
    public SaktiTxContext withRiskFlag(RiskFlag flag) {
        Map<RiskFlag, Integer> newMetrics = new HashMap<>(riskMetrics);
        newMetrics.put(flag, newMetrics.getOrDefault(flag, 0) + 1);
        return new Builder(this).riskMetrics(newMetrics).build();
    }
    
    public SaktiTxContext withResource(ResourceEnlistment resource) {
        List<ResourceEnlistment> newResources = new ArrayList<>(resources);
        if (!newResources.contains(resource)) {
            newResources.add(resource);
        }
        return new Builder(this).resources(newResources).build();
    }
    
    public SaktiTxContext withLock(String lockKey) {
        Set<String> newLocks = new HashSet<>(acquiredLocks);
        newLocks.add(lockKey);
        return new Builder(this).acquiredLocks(newLocks).build();
    }
    
    public SaktiTxContext withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put(key, value);
        return new Builder(this).metadata(newMetadata).build();
    }
    
    @Override
    public String toString() {
        return "SaktiTxContext{" +
                "txId='" + txId + '\'' +
                ", phase=" + phase +
                ", duration=" + getDuration().toMillis() + "ms" +
                ", resources=" + resources.size() +
                ", riskLevel=" + getOverallRiskLevel() +
                ", locks=" + acquiredLocks.size() +
                '}';
    }
    
    // Builder pattern
    public static class Builder {
        private String txId;
        private String businessKey;
        private TransactionPhase phase;
        private Instant startTime;
        private List<ResourceEnlistment> resources;
        private Map<RiskFlag, Integer> riskMetrics;
        private Set<String> acquiredLocks;
        private Map<String, Object> metadata;
        private ContextPropagationMode propagationMode;
        
        public Builder() {
            this.txId = UUID.randomUUID().toString();
            this.phase = TransactionPhase.CREATED;
            this.startTime = Instant.now();
            this.resources = new ArrayList<>();
            this.riskMetrics = new ConcurrentHashMap<>();
            this.acquiredLocks = new HashSet<>();
            this.metadata = new HashMap<>();
            this.propagationMode = ContextPropagationMode.REQUIRED;
        }
        
        public Builder(SaktiTxContext existing) {
            this.txId = existing.txId;
            this.businessKey = existing.businessKey;
            this.phase = existing.phase;
            this.startTime = existing.startTime;
            this.resources = new ArrayList<>(existing.resources);
            this.riskMetrics = new HashMap<>(existing.riskMetrics);
            this.acquiredLocks = new HashSet<>(existing.acquiredLocks);
            this.metadata = new HashMap<>(existing.metadata);
            this.propagationMode = existing.propagationMode;
        }
        
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
        
        public Builder resources(List<ResourceEnlistment> resources) {
            this.resources = resources;
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
        
        public Builder propagationMode(ContextPropagationMode propagationMode) {
            this.propagationMode = propagationMode;
            return this;
        }
        
        public SaktiTxContext build() {
            return new SaktiTxContext(this);
        }
    }
}