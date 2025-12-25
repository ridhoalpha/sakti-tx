package id.go.kemenkeu.djpbn.sakti.tx.core.validation;

import id.go.kemenkeu.djpbn.sakti.tx.core.context.ResourceEnlistment;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;
import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PRODUCTION-GRADE Pre-Commit Validator
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * CRITICAL FIX:
 * - Resource state validation REMOVED
 *   Reason: Resources belum di-prepare saat validation phase
 *   Check ini akan selalu WARNING di VALIDATING phase
 * 
 * FOCUS VALIDATION:
 * 1. Database connectivity (can we reach databases?)
 * 2. Transaction duration (is it too long?)
 * 3. Risk assessment (what's the overall risk?)
 * 
 * @version 2.1.0-PRODUCTION
 */
public class DefaultPreCommitValidator implements PreCommitValidator {
    
    private static final Logger log = LoggerFactory.getLogger(DefaultPreCommitValidator.class);
    
    // CHANGE: Store factories
    private final Map<String, EntityManagerFactory> entityManagerFactories;
    private final Duration longRunningThreshold;
    
    public DefaultPreCommitValidator(Map<String, EntityManagerFactory> entityManagerFactories) {
        this(entityManagerFactories, Duration.ofSeconds(30));
    }
    
    public DefaultPreCommitValidator(
            Map<String, EntityManagerFactory> entityManagerFactories,
            Duration longRunningThreshold) {
        this.entityManagerFactories = entityManagerFactories;
        this.longRunningThreshold = longRunningThreshold;
        
        log.info("DefaultPreCommitValidator initialized");
        log.info("  → Long Running Threshold: {}s", longRunningThreshold.toSeconds());
        log.info("  → Registered databases: {}", entityManagerFactories.size());
    }
    
    @Override
    public ValidationResult validate(SaktiTxContext context) {
        List<ValidationIssue> issues = new ArrayList<>();
        
        log.debug("Starting pre-commit validation for txId: {}", context.getTxId());
        
        // ═══════════════════════════════════════════════════════════════
        // CHECK 1: Database Connectivity
        // ═══════════════════════════════════════════════════════════════
        
        validateDatabaseConnectivity(context, issues);
        
        // ═══════════════════════════════════════════════════════════════
        // CHECK 2: Transaction Duration
        // ═══════════════════════════════════════════════════════════════
        
        validateTransactionDuration(context, issues);
        
        // ═══════════════════════════════════════════════════════════════
        // CHECK 3: Risk Assessment
        // ═══════════════════════════════════════════════════════════════
        
        RiskLevel overallRisk = context.getOverallRiskLevel();
        if (overallRisk == RiskLevel.CRITICAL) {
            issues.add(ValidationIssue.warning(
                "CRITICAL_RISK",
                "Transaction has CRITICAL risk level - review carefully",
                context.getTxId()
            ));
        }
        
        // ═══════════════════════════════════════════════════════════════
        // NOTE: Resource state validation REMOVED
        // Reason: Resources are prepared in PHASE 8, AFTER validation
        // This check will ALWAYS produce warnings in VALIDATING phase
        // ═══════════════════════════════════════════════════════════════
        
        // Determine if can proceed
        boolean canProceed = issues.stream()
            .noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
        
        log.debug("Pre-commit validation complete: canProceed={}, issues={}, risk={}", 
            canProceed, issues.size(), overallRisk);
        
        return new ValidationResult(canProceed, issues, overallRisk);
    }
    
    /**
     * Validate database connectivity untuk semua enlisted resources
     */
    private void validateDatabaseConnectivity(SaktiTxContext context, 
                                              List<ValidationIssue> issues) {
        int checkedCount = 0;
        int healthyCount = 0;
        
        for (ResourceEnlistment resource : context.getResources()) {
            if (!"DATABASE".equals(resource.getType())) {
                continue;
            }
            
            checkedCount++;
            String datasourceName = resource.getName();
            
            // Get EntityManagerFactory
            EntityManagerFactory emf = entityManagerFactories.get(datasourceName);
            if (emf == null) {
                // Try without TransactionManager suffix
                if (datasourceName.endsWith("TransactionManager")) {
                    String shortName = datasourceName.replace("TransactionManager", "");
                    emf = entityManagerFactories.get(shortName);
                }
                
                // Try with TransactionManager suffix
                if (emf == null && !datasourceName.endsWith("TransactionManager")) {
                    String longName = datasourceName + "TransactionManager";
                    emf = entityManagerFactories.get(longName);
                }
                
                if (emf == null) {
                    issues.add(ValidationIssue.error(
                        "DB_NOT_FOUND",
                        "EntityManagerFactory not found for resource: " + datasourceName,
                        datasourceName
                    ));
                    log.warn("EntityManagerFactory not found: {}", datasourceName);
                    log.warn("Available: {}", entityManagerFactories.keySet());
                    continue;
                }
            }
            
            // SAFE: Create temporary EM for validation
            EntityManager em = null;
            try {
                em = emf.createEntityManager();
                
                // Simple connectivity check
                em.createNativeQuery("SELECT 1").getSingleResult();
                
                healthyCount++;
                log.trace("Database connectivity OK: {}", datasourceName);
                
            } catch (Exception e) {
                issues.add(ValidationIssue.error(
                    "DB_UNREACHABLE",
                    String.format("Database unreachable: %s - %s", 
                        datasourceName, e.getMessage()),
                    datasourceName
                ));
                log.error("Database connectivity check failed: {}", datasourceName, e);
                
            } finally {
                // CLOSE temporary EM
                if (em != null && em.isOpen()) {
                    try {
                        em.close();
                    } catch (Exception e) {
                        log.warn("Failed to close validation EntityManager: {}", 
                            datasourceName);
                    }
                }
            }
        }
        
        if (checkedCount > 0) {
            log.debug("Database connectivity: {}/{} healthy", healthyCount, checkedCount);
        }
    }
    
    /**
     * Validate transaction duration
     */
    private void validateTransactionDuration(SaktiTxContext context, 
                                            List<ValidationIssue> issues) {
        Duration duration = context.getDuration();
        
        if (duration.compareTo(longRunningThreshold) > 0) {
            issues.add(ValidationIssue.warning(
                "LONG_RUNNING_TX",
                String.format(
                    "Transaction duration (%ds) exceeds threshold (%ds) - potential lock contention",
                    duration.toSeconds(), longRunningThreshold.toSeconds()
                ),
                context.getTxId()
            ));
            
            log.warn("Long-running transaction detected: {}s (threshold: {}s)", 
                duration.toSeconds(), longRunningThreshold.toSeconds());
        }
    }
}