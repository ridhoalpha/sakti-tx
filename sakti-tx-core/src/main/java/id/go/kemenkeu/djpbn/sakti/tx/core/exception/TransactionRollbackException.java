package id.go.kemenkeu.djpbn.sakti.tx.core.exception;

/**
 * Enhanced exception untuk transaction rollback dengan context detail
 * Digunakan untuk debugging dan monitoring partial commits
 */
public class TransactionRollbackException extends RuntimeException {
    
    private final String txId;
    private final int operationCount;
    private final boolean partialCommit;
    
    public TransactionRollbackException(String message) {
        super(message);
        this.txId = null;
        this.operationCount = 0;
        this.partialCommit = false;
    }
    
    public TransactionRollbackException(String message, Throwable cause) {
        super(message, cause);
        this.txId = null;
        this.operationCount = 0;
        this.partialCommit = false;
    }
    
    /**
     * Constructor dengan transaction context untuk better debugging
     */
    public TransactionRollbackException(String message, Throwable cause, 
                                       String txId, int operationCount, boolean partialCommit) {
        super(message, cause);
        this.txId = txId;
        this.operationCount = operationCount;
        this.partialCommit = partialCommit;
    }
    
    public String getTxId() {
        return txId;
    }
    
    public int getOperationCount() {
        return operationCount;
    }
    
    public boolean isPartialCommit() {
        return partialCommit;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (txId != null) {
            sb.append(" [txId=").append(txId);
            sb.append(", operations=").append(operationCount);
            sb.append(", partialCommit=").append(partialCommit);
            sb.append("]");
        }
        return sb.toString();
    }
}