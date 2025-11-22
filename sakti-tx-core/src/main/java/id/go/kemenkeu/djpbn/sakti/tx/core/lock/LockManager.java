package id.go.kemenkeu.djpbn.sakti.tx.core.lock;

public interface LockManager {
    
    LockHandle tryLock(String lockKey, long waitTimeMs, long leaseTimeMs) throws Exception;
    
    interface LockHandle extends AutoCloseable {
        boolean isAcquired();
        void release();
        
        @Override
        default void close() {
            release();
        }
    }
}