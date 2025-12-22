package id.go.kemenkeu.djpbn.sakti.tx.core.async;

import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextHolder;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for Runnable that propagates SAKTI TX context to async threads
 */
public class SaktiTxRunnable implements Runnable {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxRunnable.class);
    
    private final Runnable delegate;
    private final SaktiTxContextSnapshot snapshot;
    
    public SaktiTxRunnable(Runnable delegate) {
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
    public void run() {
        if (snapshot == null) {
            // No context to propagate - execute normally
            delegate.run();
            return;
        }
        
        // Restore context in async thread
        SaktiTxContext restored = snapshot.restore();
        SaktiTxContextHolder.set(restored);
    log.debug("Restored TX context in async thread - txId: {}, thread: {}", 
        restored.getTxId(), Thread.currentThread().getId());
    
    try {
        delegate.run();
    } finally {
        // Clean up context from async thread
        SaktiTxContextHolder.clear();
        log.trace("Cleared TX context from async thread: {}", 
            Thread.currentThread().getId());
    }
}
}