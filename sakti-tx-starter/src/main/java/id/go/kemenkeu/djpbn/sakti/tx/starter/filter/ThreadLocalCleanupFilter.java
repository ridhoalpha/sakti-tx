package id.go.kemenkeu.djpbn.sakti.tx.starter.filter;

import id.go.kemenkeu.djpbn.sakti.tx.core.listener.EntityOperationListener;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.DistributedTransactionContext;
import id.go.kemenkeu.djpbn.sakti.tx.starter.service.MultiDbTxContextHolder;
import jakarta.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * CRITICAL: ThreadLocal Cleanup Filter
 * 
 * PURPOSE:
 * - Guarantee ThreadLocal cleanup at HTTP request boundary
 * - Prevent memory leaks in thread pools
 * - Prevent cross-request data contamination
 * 
 * EXECUTION ORDER:
 * - Highest precedence (executed first, cleaned up last)
 * - Runs AFTER all other filters/interceptors
 * 
 * WHY NEEDED:
 * Even with try-finally blocks in aspects, there are edge cases where
 * ThreadLocal might not be cleaned up:
 * 1. Uncaught exceptions before aspect execution
 * 2. Framework-level errors
 * 3. Async processing edge cases
 * 4. Thread pool reuse in servlet containers
 * 
 * THREAD SAFETY:
 * This filter runs once per request, per thread.
 * Each thread gets its own ThreadLocal instance.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Execute FIRST (cleanup LAST)
public class ThreadLocalCleanupFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(ThreadLocalCleanupFilter.class);
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("ThreadLocal Cleanup Filter - INITIALIZED");
        log.info("Order: HIGHEST_PRECEDENCE (guarantees cleanup)");
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        // Record thread ID for debugging
        long threadId = Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();
        
        log.trace("Request started - Thread: {} ({})", threadId, threadName);
        
        try {
            // ═══════════════════════════════════════════════════════════════
            // Pre-check: Verify ThreadLocal is clean before processing
            // ═══════════════════════════════════════════════════════════════
            if (hasLeakedContext()) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("MEMORY LEAK DETECTED: ThreadLocal NOT cleaned from previous request!");
                log.error("Thread: {} ({})", threadId, threadName);
                log.error("This indicates a bug in cleanup logic or async operation leak");
                log.error("═══════════════════════════════════════════════════════════");
                
                // Force cleanup immediately
                forceCleanup();
            }
            
            // ═══════════════════════════════════════════════════════════════
            // Process request
            // ═══════════════════════════════════════════════════════════════
            chain.doFilter(request, response);
            
        } catch (Exception e) {
            log.error("Request failed - Thread: {} ({})", threadId, threadName, e);
            throw e;
            
        } finally {
            // ═══════════════════════════════════════════════════════════════
            // CRITICAL: GUARANTEED cleanup in finally block
            // ═══════════════════════════════════════════════════════════════
            
            log.trace("Cleaning up ThreadLocal - Thread: {} ({})", threadId, threadName);
            
            boolean hadLeaks = forceCleanup();
            
            if (hadLeaks) {
                log.warn("ThreadLocal cleanup found data from current request - Thread: {} ({})", 
                    threadId, threadName);
            }
            
            // ═══════════════════════════════════════════════════════════════
            // Final verification
            // ═══════════════════════════════════════════════════════════════
            if (hasLeakedContext()) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("CRITICAL: ThreadLocal STILL not clean after cleanup!");
                log.error("Thread: {} ({})", threadId, threadName);
                log.error("This should NEVER happen - indicates severe bug");
                log.error("═══════════════════════════════════════════════════════════");
                
                // Last resort: force remove again
                forceCleanup();
            }
            
            log.trace("Request completed - Thread: {} ({})", threadId, threadName);
        }
    }
    
    /**
     * Check if any ThreadLocal context is leaked
     */
    private boolean hasLeakedContext() {
        return EntityOperationListener.getContext() != null ||
               DistributedTransactionContext.get() != null ||
               MultiDbTxContextHolder.get() != null ||
               !MDC.getCopyOfContextMap().isEmpty();
    }
    
    /**
     * Force cleanup all ThreadLocal contexts
     * 
     * @return true if any context was found and cleaned
     */
    private boolean forceCleanup() {
        boolean hadData = false;
        
        // ═══════════════════════════════════════════════════════════════
        // 1. EntityOperationListener context
        // ═══════════════════════════════════════════════════════════════
        try {
            if (EntityOperationListener.getContext() != null) {
                EntityOperationListener.clearContext();
                hadData = true;
                log.debug("Cleaned EntityOperationListener context");
            }
        } catch (Exception e) {
            log.error("Failed to clear EntityOperationListener context", e);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // 2. DistributedTransactionContext
        // ═══════════════════════════════════════════════════════════════
        try {
            if (DistributedTransactionContext.get() != null) {
                DistributedTransactionContext.clear();
                hadData = true;
                log.debug("Cleaned DistributedTransactionContext");
            }
        } catch (Exception e) {
            log.error("Failed to clear DistributedTransactionContext", e);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // 3. MultiDbTxContextHolder
        // ═══════════════════════════════════════════════════════════════
        try {
            MultiDbTxContextHolder.clear();
            log.debug("Cleaned MultiDbTxContextHolder");
        } catch (Exception e) {
            log.error("Failed to clear MultiDbTxContextHolder", e);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // 4. MDC (Mapped Diagnostic Context)
        // ═══════════════════════════════════════════════════════════════
        try {
            if (MDC.getCopyOfContextMap() != null && !MDC.getCopyOfContextMap().isEmpty()) {
                MDC.clear();
                hadData = true;
                log.debug("Cleaned MDC");
            }
        } catch (Exception e) {
            log.error("Failed to clear MDC", e);
        }
        
        return hadData;
    }
    
    @Override
    public void destroy() {
        log.info("ThreadLocal Cleanup Filter - DESTROYED");
    }
}