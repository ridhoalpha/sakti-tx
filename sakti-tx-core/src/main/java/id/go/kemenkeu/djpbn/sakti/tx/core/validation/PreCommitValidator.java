package id.go.kemenkeu.djpbn.sakti.tx.core.validation;

import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;

/**
 * Validates transaction before committing any database
 * Prevents partial commits by checking all constraints first
 */
public interface PreCommitValidator {
    
    /**
     * Validate transaction context before commit
     * 
     * @param context Transaction context to validate
     * @return Validation result with issues and risk assessment
     */
    ValidationResult validate(SaktiTxContext context);
}