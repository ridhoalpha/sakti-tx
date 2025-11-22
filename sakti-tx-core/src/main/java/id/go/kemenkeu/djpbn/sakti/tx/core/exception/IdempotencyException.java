package id.go.kemenkeu.djpbn.sakti.tx.core.exception;

public class IdempotencyException extends RuntimeException {
    public IdempotencyException(String message) {
        super(message);
    }
    
    public IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}