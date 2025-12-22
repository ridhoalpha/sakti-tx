package id.go.kemenkeu.djpbn.sakti.tx.core.context;

/**
 * Context propagation modes for async operations
 */
public enum ContextPropagationMode {
    
    /**
     * Context required - fail if not present
     */
    REQUIRED,
    
    /**
     * Create new context if not present
     */
    REQUIRES_NEW,
    
    /**
     * Use existing context if present, otherwise continue without
     */
    SUPPORTS,
    
    /**
     * Never use transaction context
     */
    NOT_SUPPORTED,
    
    /**
     * Fail if context present
     */
    NEVER
}