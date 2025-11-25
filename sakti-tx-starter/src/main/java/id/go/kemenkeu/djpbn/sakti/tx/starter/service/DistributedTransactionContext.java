package id.go.kemenkeu.djpbn.sakti.tx.starter.service;

import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationType;
import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;

/**
 * Thread-local context untuk distributed transaction
 * Holds current transaction log and state
 */
public class DistributedTransactionContext {
    
    private static final ThreadLocal<DistributedTransactionContext> CONTEXT = new ThreadLocal<>();
    
    private final TransactionLog txLog;
    private final TransactionLogManager logManager;
    private boolean active;
    private boolean rollbackOnly;
    
    private DistributedTransactionContext(TransactionLog txLog, TransactionLogManager logManager) {
        this.txLog = txLog;
        this.logManager = logManager;
        this.active = true;
        this.rollbackOnly = false;
    }
    
    /**
     * Start new distributed transaction
     */
    public static DistributedTransactionContext start(String businessKey, TransactionLogManager logManager) {
        TransactionLog txLog = logManager.createLog(businessKey);
        DistributedTransactionContext context = new DistributedTransactionContext(txLog, logManager);
        CONTEXT.set(context);
        return context;
    }
    
    /**
     * Get current context
     */
    public static DistributedTransactionContext get() {
        return CONTEXT.get();
    }
    
    /**
     * Clear context
     */
    public static void clear() {
        CONTEXT.remove();
    }
    
    /**
     * Check if context exists and is active
     */
    public static boolean isActive() {
        DistributedTransactionContext ctx = CONTEXT.get();
        return ctx != null && ctx.active;
    }
    
    /**
     * Record operation to transaction log
     */
    public void recordOperation(String datasource, OperationType type, 
                                 String entityClass, Object entityId, 
                                 Object snapshot) {
        if (!active) {
            throw new IllegalStateException("Transaction context is not active");
        }
        
        txLog.recordOperation(datasource, type, entityClass, entityId, snapshot);
        
        // Save to Redis after each operation (for durability)
        logManager.saveLog(txLog);
    }
    
    /**
     * Mark transaction as rollback only
     */
    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }
    
    /**
     * Check if rollback only
     */
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }
    
    /**
     * Get transaction ID
     */
    public String getTransactionId() {
        return txLog.getTxId();
    }
    
    /**
     * Get transaction log
     */
    public TransactionLog getTransactionLog() {
        return txLog;
    }
    
    /**
     * Get log manager
     */
    public TransactionLogManager getLogManager() {
        return logManager;
    }
    
    /**
     * Mark transaction as completed
     */
    public void markCompleted() {
        this.active = false;
    }
    
    /**
     * Get operation count
     */
    public int getOperationCount() {
        return txLog.getOperations().size();
    }
}