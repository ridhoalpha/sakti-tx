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
 * Default pre-commit validator implementation
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
    }
    
    @Override
    public ValidationResult validate(SaktiTxContext context) {
        List<ValidationIssue> issues = new ArrayList<>();
        
        log.debug("Starting pre-commit validation for txId: {}", context.getTxId());
        
        // Check 1: Database connectivity
        validateDatabaseConnectivity(context, issues);
        
        // Check 2: Transaction duration
        validateTransactionDuration(context, issues);
        
        // Check 3: Resource state
        validateResourceState(context, issues);
        
        // Check 4: Risk assessment
        RiskLevel overallRisk = context.getOverallRiskLevel();
        if (overallRisk == RiskLevel.CRITICAL) {
            issues.add(ValidationIssue.warning(
                "CRITICAL_RISK",
                "Transaction has CRITICAL risk level - review carefully",
                context.getTxId()
            ));
        }
        
        // Determine if can proceed
        boolean canProceed = issues.stream()
            .noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
        
        log.debug("Pre-commit validation complete: canProceed={}, issues={}, risk={}", 
            canProceed, issues.size(), overallRisk);
        
        return new ValidationResult(canProceed, issues, overallRisk);
    }
    
    private void validateDatabaseConnectivity(SaktiTxContext context, 
                                              List<ValidationIssue> issues) {
        for (ResourceEnlistment resource : context.getResources()) {
            if (!"DATABASE".equals(resource.getType())) {
                continue;
            }
            
            EntityManagerFactory emf = entityManagerFactories.get(resource.getName());
            if (emf == null) {
                issues.add(ValidationIssue.error(
                    "DB_NOT_FOUND",
                    "EntityManagerFactory not found for resource: " + resource.getName(),
                    resource.getName()
                ));
                continue;
            }
            
            // SAFE: Create temporary EM for validation
            try (EntityManager em = emf.createEntityManager()) {
                em.createNativeQuery("SELECT 1").getSingleResult();
                log.trace("Database connectivity OK: {}", resource.getName());
            } catch (Exception e) {
                issues.add(ValidationIssue.error(
                    "DB_UNREACHABLE",
                    "Database unreachable: " + resource.getName() + " - " + e.getMessage(),
                    resource.getName()
                ));
            }
        }
    }
    
    private void validateTransactionDuration(SaktiTxContext context, 
                                            List<ValidationIssue> issues) {
        Duration duration = context.getDuration();
        
        if (duration.compareTo(longRunningThreshold) > 0) {
            issues.add(ValidationIssue.warning(
                "LONG_RUNNING_TX",
                "Transaction duration (" + duration.toSeconds() + "s) exceeds threshold (" +
                    longRunningThreshold.toSeconds() + "s) - potential lock contention",
                context.getTxId()
            ));
        }
    }
    
    private void validateResourceState(SaktiTxContext context, 
                                       List<ValidationIssue> issues) {
        for (ResourceEnlistment resource : context.getResources()) {
            if (!resource.isPrepared()) {
                issues.add(ValidationIssue.warning(
                    "RESOURCE_NOT_PREPARED",
                    "Resource not in prepared state: " + resource.getName(),
                    resource.getName()
                ));
            }
        }
    }
}