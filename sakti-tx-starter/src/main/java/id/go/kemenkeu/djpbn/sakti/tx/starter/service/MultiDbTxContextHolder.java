package id.go.kemenkeu.djpbn.sakti.tx.starter.service;

import id.go.kemenkeu.djpbn.sakti.tx.core.transaction.MultiDbTransactionManager;

/**
 * Thread-local holder for multi-DB transaction context
 */
public class MultiDbTxContextHolder {
    
    private static final ThreadLocal<MultiDbTransactionManager> CONTEXT = new ThreadLocal<>();
    
    public static void set(MultiDbTransactionManager txManager) {
        CONTEXT.set(txManager);
    }
    
    public static MultiDbTransactionManager get() {
        MultiDbTransactionManager txManager = CONTEXT.get();
        if (txManager == null) {
            txManager = new MultiDbTransactionManager();
            CONTEXT.set(txManager);
        }
        return txManager;
    }
    
    public static void clear() {
        CONTEXT.remove();
    }
}