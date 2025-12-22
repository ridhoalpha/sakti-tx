package id.go.kemenkeu.djpbn.sakti.tx.core.validation;

import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of pre-commit validation
 */
public class ValidationResult {
    
    private final boolean canProceed;
    private final List<ValidationIssue> issues;
    private final RiskLevel overallRisk;
    
    public ValidationResult(boolean canProceed, List<ValidationIssue> issues, 
                           RiskLevel overallRisk) {
        this.canProceed = canProceed;
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.overallRisk = overallRisk;
    }
    
    public boolean canProceed() {
        return canProceed;
    }
    
    public List<ValidationIssue> getIssues() {
        return issues;
    }
    
    public RiskLevel getOverallRisk() {
        return overallRisk;
    }
    
    public boolean hasErrors() {
        return issues.stream()
            .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
    }
    
    public boolean hasWarnings() {
        return issues.stream()
            .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.WARNING);
    }
    
    public List<ValidationIssue> getErrors() {
        return issues.stream()
            .filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)
            .collect(Collectors.toList());
    }
    
    public List<ValidationIssue> getWarnings() {
        return issues.stream()
            .filter(i -> i.getSeverity() == ValidationIssue.Severity.WARNING)
            .collect(Collectors.toList());
    }
    
    public static ValidationResult success(RiskLevel risk) {
        return new ValidationResult(true, Collections.emptyList(), risk);
    }
    
    public static ValidationResult failure(List<ValidationIssue> issues, RiskLevel risk) {
        return new ValidationResult(false, issues, risk);
    }
    
    @Override
    public String toString() {
        return String.format("ValidationResult{canProceed=%s, issues=%d, risk=%s}", 
            canProceed, issues.size(), overallRisk);
    }
}