package id.go.kemenkeu.djpbn.sakti.tx.core.async;

import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextHolder;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * Wrapper for Callable that propagates SAKTI TX context to async threads
 */
public class SaktiTxCallable<V> implements Callable<V> {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxCallable.class);
    
    private final Callable<V> delegate;
    private final SaktiTxContextSnapshot snapshot;
    
    public SaktiTxCallable(Callable<V> delegate) {
        this.delegate = delegate;
        
        // Capture context NOW (in parent thread)
        if (SaktiTxContextHolder.hasContext()) {
            this.snapshot = SaktiTxContextSnapshot.capture();
            log.debug("Captured TX context for async execution: {}", snapshot.getTxId());
        } else {
            this.snapshot = null;
            log.trace("No TX context to capture for async execution");
        }
    }
    
    @Override
    public V call() throws Exception {
        if (snapshot == null) {
            // No context to propagate - execute normally
            return delegate.call();
        }
        
        // Restore context in async thread
        SaktiTxContext restored = snapshot.restore();
        SaktiTxContextHolder.set(restored);
        
        log.debug("Restored TX context in async thread - txId: {}, thread: {}", 
            restored.getTxId(), Thread.currentThread().getId());
        
        try {
            return delegate.call();
        } finally {
            // Clean up context from async thread
            SaktiTxContextHolder.clear();
            log.trace("Cleared TX context from async thread: {}", 
                Thread.currentThread().getId());
        }
    }
}