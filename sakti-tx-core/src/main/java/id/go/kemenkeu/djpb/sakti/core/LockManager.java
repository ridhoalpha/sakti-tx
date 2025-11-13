package id.go.kemenkeu.djpbn.sakti.core;

public interface LockManager {
    LockHandle tryLock(String key, long waitMillis, long leaseMillis) throws Exception;

    interface LockHandle extends AutoCloseable {
        boolean isAcquired();
        void release();
        @Override default void close() { release(); }
    }
}
