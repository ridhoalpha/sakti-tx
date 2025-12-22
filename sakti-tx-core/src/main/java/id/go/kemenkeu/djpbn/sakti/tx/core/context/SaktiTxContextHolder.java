package id.go.kemenkeu.djpbn.sakti.tx.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * ThreadLocal holder for transaction context
 * Separated from context itself to support async propagation
 */
public class SaktiTxContextHolder {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxContextHolder.class);
    
    private static final ThreadLocal<ContextHandle> CONTEXT_HANDLE = 
        ThreadLocal.withInitial(() -> null);
    
    /**
     * Get current context
     */
    public static SaktiTxContext get() {
        ContextHandle handle = CONTEXT_HANDLE.get();
        return handle != null ? handle.context : null;
    }
    
    /**
     * Set context for current thread
     */
    public static void set(SaktiTxContext context) {
        if (context == null) {
            CONTEXT_HANDLE.remove();
            return;
        }
        
        ContextHandle handle = new ContextHandle(context);
        CONTEXT_HANDLE.set(handle);
        
        log.trace("Context bound to thread {} - txId: {}", 
            Thread.currentThread().getId(), context.getTxId());
    }
    
    /**
     * Update context (immutable pattern)
     */
    public static void update(SaktiTxContext newContext) {
        ContextHandle handle = CONTEXT_HANDLE.get();
        if (handle == null) {
            throw new IllegalStateException("No context bound to current thread");
        }
        
        handle.context = newContext;
        
        log.trace("Context updated in thread {} - phase: {}", 
            Thread.currentThread().getId(), newContext.getPhase());
    }
    
    /**
     * Clear context from current thread
     */
    public static void clear() {
        ContextHandle handle = CONTEXT_HANDLE.get();
        
        if (handle != null) {
            log.trace("Context cleared from thread {} - txId: {}", 
                Thread.currentThread().getId(), handle.context.getTxId());
            
            CONTEXT_HANDLE.remove();
        }
    }
    
    /**
     * Check if context exists
     */
    public static boolean hasContext() {
        return CONTEXT_HANDLE.get() != null;
    }
    
    /**
     * Handle to track context binding
     */
    private static class ContextHandle {
        private SaktiTxContext context;
        private final long threadId;
        private final Instant boundAt;
        
        ContextHandle(SaktiTxContext context) {
            this.context = context;
            this.threadId = Thread.currentThread().getId();
            this.boundAt = Instant.now();
        }
    }
}