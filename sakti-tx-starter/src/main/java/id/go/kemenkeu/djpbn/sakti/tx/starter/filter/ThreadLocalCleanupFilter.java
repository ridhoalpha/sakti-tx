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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ThreadLocalCleanupFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(ThreadLocalCleanupFilter.class);
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        long threadId = Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();
        
        try {
            // Pre-check: Verify clean state before processing
            boolean hadLeak = checkAndCleanLeakedContext();
            
            if (hadLeak) {
                log.warn("ThreadLocal leak detected before request - cleaned - thread: {} ({})", 
                    threadId, threadName);
            }
            
            // Process request
            chain.doFilter(request, response);
            
        } finally {
            // CRITICAL: GUARANTEED cleanup
            boolean hadData = forceCleanupAll();
            
            if (hadData) {
                log.debug("Cleaned ThreadLocal data after request - thread: {} ({})", 
                    threadId, threadName);
            }
            
            // Final verification
            if (hasLeakedContext()) {
                log.error("CRITICAL: ThreadLocal STILL leaked after cleanup - thread: {} ({})", 
                    threadId, threadName);
                // Emergency force cleanup
                forceCleanupAll();
            }
        }
    }
    
    /**
     * Check if any ThreadLocal context leaked from previous request
     * Returns true if leak detected and cleaned
     */
    private boolean checkAndCleanLeakedContext() {
        if (hasLeakedContext()) {
            forceCleanupAll();
            return true;
        }
        return false;
    }
    
    /**
     * Check if any ThreadLocal context is leaked
     */
    private boolean hasLeakedContext() {
        boolean hasLeak = false;
        
        // Check EntityOperationListener context
        if (EntityOperationListener.getOperationContext() != null) {
            hasLeak = true;
        }
        
        // Check DistributedTransactionContext
        if (DistributedTransactionContext.get() != null) {
            hasLeak = true;
        }
        
        // Check MultiDbTxContextHolder
        try {
            if (MultiDbTxContextHolder.get() != null) {
                hasLeak = true;
            }
        } catch (Exception e) {
            // Ignore
        }
        
        // Check MDC
        if (MDC.getCopyOfContextMap() != null && !MDC.getCopyOfContextMap().isEmpty()) {
            hasLeak = true;
        }
        
        return hasLeak;
    }
    
    /**
     * Force cleanup ALL ThreadLocal contexts
     * Returns true if any data was found and cleaned
     */
    private boolean forceCleanupAll() {
        boolean hadData = false;
        
        // 1. EntityOperationListener context
        try {
            if (EntityOperationListener.getOperationContext() != null) {
                EntityOperationListener.clearOperationContext();
                hadData = true;
                
                // Double-check
                if (EntityOperationListener.getOperationContext() != null) {
                    log.error("EntityOperationListener context STILL not cleared!");
                    
                    // Try direct manipulation (risky but necessary)
                    try {
                        java.lang.reflect.Field field = EntityOperationListener.class
                            .getDeclaredField("OPERATION_CONTEXT");
                        field.setAccessible(true);
                        ThreadLocal<?> tl = (ThreadLocal<?>) field.get(null);
                        tl.remove();
                    } catch (Exception e) {
                        log.error("Failed to force clear via reflection", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to clear EntityOperationListener context", e);
        }
        
        // 2. DistributedTransactionContext
        try {
            if (DistributedTransactionContext.get() != null) {
                DistributedTransactionContext.clear();
                hadData = true;
            }
        } catch (Exception e) {
            log.error("Failed to clear DistributedTransactionContext", e);
        }
        
        // 3. MultiDbTxContextHolder
        try {
            MultiDbTxContextHolder.clear();
        } catch (Exception e) {
            log.error("Failed to clear MultiDbTxContextHolder", e);
        }
        
        // 4. MDC
        try {
            if (MDC.getCopyOfContextMap() != null && !MDC.getCopyOfContextMap().isEmpty()) {
                MDC.clear();
                hadData = true;
            }
        } catch (Exception e) {
            log.error("Failed to clear MDC", e);
        }
        
        return hadData;
    }
}