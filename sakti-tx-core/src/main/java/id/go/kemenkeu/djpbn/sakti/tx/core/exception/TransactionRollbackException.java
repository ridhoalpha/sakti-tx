package id.go.kemenkeu.djpbn.sakti.tx.core.exception;

public class TransactionRollbackException extends RuntimeException {
    public TransactionRollbackException(String message) {
        super(message);
    }
    
    public TransactionRollbackException(String message, Throwable cause) {
        super(message, cause);
    }
}