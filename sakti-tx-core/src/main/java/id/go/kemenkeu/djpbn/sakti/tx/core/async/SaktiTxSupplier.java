package id.go.kemenkeu.djpbn.sakti.tx.core.async;

import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextHolder;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Wrapper for Supplier that propagates SAKTI TX context
 */
public class SaktiTxSupplier<T> implements Supplier<T> {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxSupplier.class);
    
    private final Supplier<T> delegate;
    private final SaktiTxContextSnapshot snapshot;
    
    public SaktiTxSupplier(Supplier<T> delegate) {
        this.delegate = delegate;
        this.snapshot = SaktiTxContextHolder.hasContext() 
            ? SaktiTxContextSnapshot.capture() 
            : null;
    }
    
    @Override
    public T get() {
        if (snapshot == null) {
            return delegate.get();
        }
        
        SaktiTxContext restored = snapshot.restore();
        SaktiTxContextHolder.set(restored);
        
        try {
            return delegate.get();
        } finally {
            SaktiTxContextHolder.clear();
        }
    }
}