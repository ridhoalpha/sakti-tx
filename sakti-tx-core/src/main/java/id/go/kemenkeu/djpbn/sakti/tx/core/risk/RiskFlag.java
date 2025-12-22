package id.go.kemenkeu.djpbn.sakti.tx.core.risk;

/**
 * Risk classification for operations
 * Allows operations but makes risks observable
 */
public enum RiskFlag {
    
    NATIVE_SQL(
        "Native SQL query used - may have side effects not tracked by JPA",
        RiskLevel.HIGH
    ),
    
    BULK_UPDATE(
        "Bulk update operation - affects multiple rows, expensive compensation",
        RiskLevel.MEDIUM
    ),
    
    BULK_DELETE(
        "Bulk delete operation - may trigger cascades",
        RiskLevel.HIGH
    ),
    
    STORED_PROCEDURE(
        "Stored procedure call - internal logic unknown to SAKTI TX",
        RiskLevel.HIGH
    ),
    
    TRIGGER_SUSPECTED(
        "Entity table has database triggers - side effects not tracked",
        RiskLevel.CRITICAL
    ),
    
    CASCADE_DELETE(
        "Entity has @OneToMany/@ManyToMany with cascade - children not explicitly tracked",
        RiskLevel.HIGH
    ),
    
    LARGE_BATCH(
        "Batch operation exceeds threshold - memory/performance concern",
        RiskLevel.MEDIUM
    ),
    
    ASYNC_OPERATION(
        "Async operation detected - ensure context propagation",
        RiskLevel.MEDIUM
    ),
    
    LONG_RUNNING(
        "Transaction duration exceeds threshold - lock contention risk",
        RiskLevel.MEDIUM
    ),
    
    EXTERNAL_API_CALL(
        "External API called - rollback may not be possible",
        RiskLevel.CRITICAL
    ),
    
    VERSION_CONFLICT(
        "Optimistic locking conflict detected during compensation",
        RiskLevel.HIGH
    ),
    
    BUSINESS_KEY_MISMATCH(
        "Entity not found by primary key, using business key fallback",
        RiskLevel.MEDIUM
    ),
    
    SOFT_DELETE_FALLBACK(
        "Hard delete not possible, using soft delete",
        RiskLevel.LOW
    );
    
    private final String description;
    private final RiskLevel level;
    
    RiskFlag(String description, RiskLevel level) {
        this.description = description;
        this.level = level;
    }
    
    public String getDescription() {
        return description;
    }
    
    public RiskLevel getLevel() {
        return level;
    }
}