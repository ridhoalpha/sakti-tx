package id.go.kemenkeu.djpbn.sakti.tx.core.log;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction log untuk tracking distributed operations
 * ENHANCED VERSION - Support complex scenarios:
 * - Bulk operations dengan affected entities
 * - Native queries dengan inverse queries
 * - Stored procedures dengan inverse procedures
 * - Custom compensation handlers
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionLog {
    
    private String txId;
    private String businessKey;
    private TransactionState state;
    private Instant startTime;
    private Instant endTime;
    private List<OperationLog> operations;
    private String errorMessage;
    private int retryCount;
    private Instant lastRetryTime;
    private Map<String, Object> metadata; // Additional context
    
    public enum TransactionState {
        STARTED,      // Transaction dimulai
        IN_PROGRESS,  // Sedang diproses
        COMMITTING,   // Sedang commit phase
        COMMITTED,    // Berhasil commit semua
        ROLLING_BACK, // Sedang rollback
        ROLLED_BACK,  // Berhasil rollback
        FAILED,       // Gagal rollback (butuh manual intervention)
        COMPENSATED   // Compensating transaction selesai
    }
    
    public TransactionLog() {
        this.txId = UUID.randomUUID().toString();
        this.state = TransactionState.STARTED;
        this.startTime = Instant.now();
        this.operations = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.retryCount = 0;
    }
    
    public TransactionLog(String businessKey) {
        this();
        this.businessKey = businessKey;
    }
    
    /**
     * Record simple entity operation (INSERT/UPDATE/DELETE)
     */
    public void recordOperation(String datasource, OperationType type, 
                                String entityClass, Object entityId, 
                                Object snapshot) {
        OperationLog op = new OperationLog();
        op.setSequence(operations.size() + 1);
        op.setDatasource(datasource);
        op.setOperationType(type);
        op.setEntityClass(entityClass);
        op.setEntityId(entityId);
        op.setSnapshot(snapshot);
        op.setTimestamp(Instant.now());
        op.setCompensated(false);
        
        operations.add(op);
    }
    
    /**
     * Record bulk operation dengan affected entities
     * Untuk: BULK_UPDATE, BULK_DELETE
     */
    public void recordBulkOperation(String datasource, OperationType type,
                                   String entityClass, 
                                   List<Map<String, Object>> affectedEntities,
                                   String queryInfo) {
        OperationLog op = new OperationLog();
        op.setSequence(operations.size() + 1);
        op.setDatasource(datasource);
        op.setOperationType(type);
        op.setEntityClass(entityClass);
        op.setAffectedEntities(affectedEntities);
        op.setAdditionalInfo(queryInfo);
        op.setTimestamp(Instant.now());
        op.setCompensated(false);
        
        operations.add(op);
    }
    
    /**
     * Record native query operation dengan inverse query
     */
    public void recordNativeQuery(String datasource,
                                  String entityClass,
                                  Object entityId,
                                  Object snapshot,
                                  String originalQuery,
                                  String inverseQuery,
                                  Map<String, Object> queryParameters) {
        OperationLog op = new OperationLog();
        op.setSequence(operations.size() + 1);
        op.setDatasource(datasource);
        op.setOperationType(OperationType.NATIVE_QUERY);
        op.setEntityClass(entityClass);
        op.setEntityId(entityId);
        op.setSnapshot(snapshot);
        op.setAdditionalInfo(originalQuery);
        op.setInverseQuery(inverseQuery);
        op.setQueryParameters(queryParameters);
        op.setTimestamp(Instant.now());
        op.setCompensated(false);
        
        operations.add(op);
    }
    
    /**
     * Record stored procedure call dengan inverse procedure
     */
    public void recordStoredProcedure(String datasource,
                                     String procedureName,
                                     String inverseProcedure,
                                     Map<String, Object> parameters,
                                     List<Map<String, Object>> affectedEntities) {
        OperationLog op = new OperationLog();
        op.setSequence(operations.size() + 1);
        op.setDatasource(datasource);
        op.setOperationType(OperationType.STORED_PROCEDURE);
        op.setAdditionalInfo(procedureName);
        op.setInverseProcedure(inverseProcedure);
        op.setQueryParameters(parameters);
        op.setAffectedEntities(affectedEntities);
        op.setTimestamp(Instant.now());
        op.setCompensated(false);
        
        operations.add(op);
    }
    
    /**
     * Mark transaction as committed
     */
    public void markCommitted() {
        this.state = TransactionState.COMMITTED;
        this.endTime = Instant.now();
    }
    
    /**
     * Mark transaction as rolling back
     */
    public void markRollingBack(String errorMessage) {
        this.state = TransactionState.ROLLING_BACK;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Mark transaction as rolled back
     */
    public void markRolledBack() {
        this.state = TransactionState.ROLLED_BACK;
        this.endTime = Instant.now();
    }
    
    /**
     * Mark transaction as failed (needs manual intervention)
     */
    public void markFailed(String reason) {
        this.state = TransactionState.FAILED;
        this.errorMessage = reason;
        this.endTime = Instant.now();
    }
    
    /**
     * Increment retry count
     */
    public void incrementRetry() {
        this.retryCount++;
        this.lastRetryTime = Instant.now();
    }
    
    /**
     * Get operations in reverse order for rollback
     */
    public List<OperationLog> getOperationsInReverseOrder() {
        List<OperationLog> reversed = new ArrayList<>(operations);
        reversed.sort((a, b) -> Integer.compare(b.getSequence(), a.getSequence()));
        return reversed;
    }
    
    /**
     * Add metadata
     */
    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
    
    /**
     * Get metadata
     */
    public Object getMetadata(String key) {
        return this.metadata.get(key);
    }
    
    /**
     * Serialize to JSON
     */
    public String toJson(ObjectMapper mapper) {
        try {
            ensureJsr310Module(mapper);
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize transaction log: " + e.getMessage(), e);
        }
    }
    
    /**
     * Deserialize from JSON
     */
    public static TransactionLog fromJson(String json, ObjectMapper mapper) {
        try {
            // Ensure JSR-310 module is registered for Instant/LocalDateTime support
            ensureJsr310Module(mapper);
            return mapper.readValue(json, TransactionLog.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize transaction log: " + e.getMessage(), e);
        }
    }
    
    /**
     * Ensure ObjectMapper has JavaTimeModule registered
     */
    private static void ensureJsr310Module(ObjectMapper mapper) {
        // Check if JavaTimeModule already registered
        boolean hasModule = mapper.getRegisteredModuleIds().stream()
            .anyMatch(id -> ((String) id).contains("JavaTimeModule") || ((String) id).contains("jsr310"));
        
        if (!hasModule) {
            // Register JavaTimeModule
            mapper.registerModule(new JavaTimeModule());
            // Disable timestamp serialization (use ISO-8601 strings instead)
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }
    
    // Getters & Setters
    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    
    public String getBusinessKey() { return businessKey; }
    public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    
    public TransactionState getState() { return state; }
    public void setState(TransactionState state) { this.state = state; }
    
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    
    public List<OperationLog> getOperations() { return operations; }
    public void setOperations(List<OperationLog> operations) { this.operations = operations; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    
    public Instant getLastRetryTime() { return lastRetryTime; }
    public void setLastRetryTime(Instant lastRetryTime) { this.lastRetryTime = lastRetryTime; }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    
    /**
     * Operation type enum
     */
    public enum OperationType {
        INSERT,           // save() baru
        UPDATE,           // save() existing
        DELETE,           // delete()
        BULK_UPDATE,      // JPQL: UPDATE ... WHERE ...
        BULK_DELETE,      // JPQL: DELETE ... WHERE ...
        NATIVE_QUERY,     // @Query(nativeQuery=true) @Modifying
        STORED_PROCEDURE  // @Procedure atau CALL ...
    }
    
    /**
     * Individual operation log - ENHANCED
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OperationLog {
        private int sequence;
        private String datasource;
        private OperationType operationType;
        private String entityClass;
        private Object entityId;
        private Object snapshot;  // Snapshot BEFORE operation (single entity)
        private Instant timestamp;
        private boolean compensated;
        private String compensationError;
        
        // ENHANCED FIELDS untuk complex operations
        private List<Map<String, Object>> affectedEntities;  // Snapshot untuk bulk operations
        private String additionalInfo;                       // Query text, procedure name, etc.
        private String inverseQuery;                         // Inverse query untuk native query
        private String inverseProcedure;                     // Inverse procedure name
        private Map<String, Object> queryParameters;         // Parameters untuk query/procedure
        private String compensationHandlerClass;             // Custom compensation handler (optional)
        
        // Getters & Setters
        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }
        
        public String getDatasource() { return datasource; }
        public void setDatasource(String datasource) { this.datasource = datasource; }
        
        public OperationType getOperationType() { return operationType; }
        public void setOperationType(OperationType operationType) { 
            this.operationType = operationType; 
        }
        
        public String getEntityClass() { return entityClass; }
        public void setEntityClass(String entityClass) { this.entityClass = entityClass; }
        
        public Object getEntityId() { return entityId; }
        public void setEntityId(Object entityId) { this.entityId = entityId; }
        
        public Object getSnapshot() { return snapshot; }
        public void setSnapshot(Object snapshot) { this.snapshot = snapshot; }
        
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        
        public boolean isCompensated() { return compensated; }
        public void setCompensated(boolean compensated) { this.compensated = compensated; }
        
        public String getCompensationError() { return compensationError; }
        public void setCompensationError(String error) { this.compensationError = error; }
        
        // ENHANCED GETTERS/SETTERS
        public List<Map<String, Object>> getAffectedEntities() { return affectedEntities; }
        public void setAffectedEntities(List<Map<String, Object>> affectedEntities) { 
            this.affectedEntities = affectedEntities; 
        }
        
        public String getAdditionalInfo() { return additionalInfo; }
        public void setAdditionalInfo(String additionalInfo) { 
            this.additionalInfo = additionalInfo; 
        }
        
        public String getInverseQuery() { return inverseQuery; }
        public void setInverseQuery(String inverseQuery) { 
            this.inverseQuery = inverseQuery; 
        }
        
        public String getInverseProcedure() { return inverseProcedure; }
        public void setInverseProcedure(String inverseProcedure) { 
            this.inverseProcedure = inverseProcedure; 
        }
        
        public Map<String, Object> getQueryParameters() { return queryParameters; }
        public void setQueryParameters(Map<String, Object> queryParameters) { 
            this.queryParameters = queryParameters; 
        }
        
        public String getCompensationHandlerClass() { return compensationHandlerClass; }
        public void setCompensationHandlerClass(String compensationHandlerClass) { 
            this.compensationHandlerClass = compensationHandlerClass; 
        }
    }
}