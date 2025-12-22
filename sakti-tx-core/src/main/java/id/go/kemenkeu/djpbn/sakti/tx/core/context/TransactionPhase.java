package id.go.kemenkeu.djpbn.sakti.tx.core.context;

/**
 * Transaction lifecycle phases
 * Prevents partial commits by adding validation phase
 */
public enum TransactionPhase {
    
    /**
     * Context created, no operations yet
     */
    CREATED,
    
    /**
     * Operations being tracked (collecting snapshots)
     */
    COLLECTING,
    
    /**
     * Pre-flight validation (NO database writes!)
     * - Check database connectivity
     * - Validate business invariants
     * - Assess risks
     */
    VALIDATING,
    
    /**
     * Ready to commit (2PC prepare phase)
     * All validations passed
     */
    PREPARED,
    
    /**
     * Executing commits to all databases
     */
    COMMITTING,
    
    /**
     * All databases committed successfully
     */
    COMMITTED,
    
    /**
     * Executing compensating operations
     */
    ROLLING_BACK,
    
    /**
     * All compensations successful
     */
    ROLLED_BACK,
    
    /**
     * Rollback failed - manual intervention required
     */
    FAILED,
    
    /**
     * Legacy compatibility
     */
    COMPENSATED;
    
    public boolean isActive() {
        return this == COLLECTING || this == VALIDATING || 
               this == PREPARED || this == COMMITTING;
    }
    
    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLED_BACK || 
               this == FAILED || this == COMPENSATED;
    }
    
    public boolean canTransitionTo(TransactionPhase next) {
        switch (this) {
            case CREATED:
                return next == COLLECTING;
            case COLLECTING:
                return next == VALIDATING || next == ROLLING_BACK;
            case VALIDATING:
                return next == PREPARED || next == ROLLING_BACK;
            case PREPARED:
                return next == COMMITTING || next == ROLLING_BACK;
            case COMMITTING:
                return next == COMMITTED || next == ROLLING_BACK;
            case ROLLING_BACK:
                return next == ROLLED_BACK || next == FAILED;
            default:
                return false;
        }
    }
}