package id.go.kemenkeu.djpbn.sakti.tx.starter.admin.dto;

import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DTO untuk transaction status di admin API
 * Provides human-readable format untuk monitoring
 */
public class TransactionStatusDto {
    
    private String txId;
    private String businessKey;
    private String state;
    private Instant startTime;
    private Instant endTime;
    private Long durationMs;
    private String errorMessage;
    private int retryCount;
    private Instant lastRetryTime;
    private int operationCount;
    private List<OperationDto> operations;
    
    // Default constructor for JSON serialization
    public TransactionStatusDto() {}
    
    /**
     * Convert TransactionLog to DTO
     */
    public static TransactionStatusDto fromTransactionLog(TransactionLog txLog) {
        TransactionStatusDto dto = new TransactionStatusDto();
        
        dto.txId = txLog.getTxId();
        dto.businessKey = txLog.getBusinessKey();
        dto.state = txLog.getState() != null ? txLog.getState().toString() : "UNKNOWN";
        dto.startTime = txLog.getStartTime();
        dto.endTime = txLog.getEndTime();
        
        if (txLog.getStartTime() != null) {
            Instant endRef = txLog.getEndTime() != null ? txLog.getEndTime() : Instant.now();
            dto.durationMs = Duration.between(txLog.getStartTime(), endRef).toMillis();
        }
        
        dto.errorMessage = txLog.getErrorMessage();
        dto.retryCount = txLog.getRetryCount();
        dto.lastRetryTime = txLog.getLastRetryTime();
        dto.operationCount = txLog.getOperations().size();
        
        // Convert operations
        dto.operations = txLog.getOperations().stream()
            .map(OperationDto::fromOperationLog)
            .collect(Collectors.toList());
        
        return dto;
    }
    
    // Getters and Setters
    
    public String getTxId() {
        return txId;
    }
    
    public void setTxId(String txId) {
        this.txId = txId;
    }
    
    public String getBusinessKey() {
        return businessKey;
    }
    
    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }
    
    public String getState() {
        return state;
    }
    
    public void setState(String state) {
        this.state = state;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }
    
    public Instant getEndTime() {
        return endTime;
    }
    
    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }
    
    public Long getDurationMs() {
        return durationMs;
    }
    
    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public Instant getLastRetryTime() {
        return lastRetryTime;
    }
    
    public void setLastRetryTime(Instant lastRetryTime) {
        this.lastRetryTime = lastRetryTime;
    }
    
    public int getOperationCount() {
        return operationCount;
    }
    
    public void setOperationCount(int operationCount) {
        this.operationCount = operationCount;
    }
    
    public List<OperationDto> getOperations() {
        return operations;
    }
    
    public void setOperations(List<OperationDto> operations) {
        this.operations = operations;
    }
    
    /**
     * Nested DTO untuk operation details
     */
    public static class OperationDto {
        private int sequence;
        private String datasource;
        private String operationType;
        private String entityClass;
        private Object entityId;
        private Instant timestamp;
        private boolean compensated;
        private String compensationError;
        private String additionalInfo;
        
        public static OperationDto fromOperationLog(TransactionLog.OperationLog opLog) {
            OperationDto dto = new OperationDto();
            
            dto.sequence = opLog.getSequence();
            dto.datasource = opLog.getDatasource();
            dto.operationType = opLog.getOperationType() != null 
                ? opLog.getOperationType().toString() 
                : "UNKNOWN";
            dto.entityClass = opLog.getEntityClass();
            dto.entityId = opLog.getEntityId();
            dto.timestamp = opLog.getTimestamp();
            dto.compensated = opLog.isCompensated();
            dto.compensationError = opLog.getCompensationError();
            dto.additionalInfo = opLog.getAdditionalInfo();
            
            return dto;
        }
        
        // Getters and Setters
        
        public int getSequence() {
            return sequence;
        }
        
        public void setSequence(int sequence) {
            this.sequence = sequence;
        }
        
        public String getDatasource() {
            return datasource;
        }
        
        public void setDatasource(String datasource) {
            this.datasource = datasource;
        }
        
        public String getOperationType() {
            return operationType;
        }
        
        public void setOperationType(String operationType) {
            this.operationType = operationType;
        }
        
        public String getEntityClass() {
            return entityClass;
        }
        
        public void setEntityClass(String entityClass) {
            this.entityClass = entityClass;
        }
        
        public Object getEntityId() {
            return entityId;
        }
        
        public void setEntityId(Object entityId) {
            this.entityId = entityId;
        }
        
        public Instant getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(Instant timestamp) {
            this.timestamp = timestamp;
        }
        
        public boolean isCompensated() {
            return compensated;
        }
        
        public void setCompensated(boolean compensated) {
            this.compensated = compensated;
        }
        
        public String getCompensationError() {
            return compensationError;
        }
        
        public void setCompensationError(String compensationError) {
            this.compensationError = compensationError;
        }
        
        public String getAdditionalInfo() {
            return additionalInfo;
        }
        
        public void setAdditionalInfo(String additionalInfo) {
            this.additionalInfo = additionalInfo;
        }
    }
}