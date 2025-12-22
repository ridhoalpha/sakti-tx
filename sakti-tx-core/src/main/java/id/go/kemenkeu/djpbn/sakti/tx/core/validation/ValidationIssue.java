package id.go.kemenkeu.djpbn.sakti.tx.core.validation;

/**
 * Represents a validation issue found during pre-commit validation
 */
public class ValidationIssue {
    
    private final Severity severity;
    private final String code;
    private final String message;
    private final String resourceId;
    private final String details;
    
    private ValidationIssue(Severity severity, String code, String message, 
                           String resourceId, String details) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.resourceId = resourceId;
        this.details = details;
    }
    
    public static ValidationIssue error(String code, String message, String resourceId) {
        return new ValidationIssue(Severity.ERROR, code, message, resourceId, null);
    }
    
    public static ValidationIssue warning(String code, String message, String resourceId) {
        return new ValidationIssue(Severity.WARNING, code, message, resourceId, null);
    }
    
    public static ValidationIssue info(String code, String message, String resourceId) {
        return new ValidationIssue(Severity.INFO, code, message, resourceId, null);
    }
    
    public enum Severity {
        ERROR,   // Blocks commit
        WARNING, // Logs but allows commit
        INFO     // Informational only
    }
    
    // Getters
    public Severity getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getResourceId() { return resourceId; }
    public String getDetails() { return details; }
    
    @Override
    public String toString() {
        return String.format("[%s] %s: %s (resource: %s)", 
            severity, code, message, resourceId);
    }
}