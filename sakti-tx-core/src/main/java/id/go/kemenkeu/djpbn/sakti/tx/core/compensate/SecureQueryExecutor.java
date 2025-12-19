package id.go.kemenkeu.djpbn.sakti.tx.core.compensate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;


/**
 * USAGE EXAMPLE - How to use in CompensatingTransactionExecutor:
 */
// class CompensationExample {
    
//     public void compensateNativeQuerySecure(EntityManager em, 
//                                             id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationLog op) {
        
//         if (op.getInverseQuery() != null && !op.getInverseQuery().isEmpty()) {
//             // ═══════════════════════════════════════════════════════════════
//             // BEFORE (UNSAFE):
//             // Query query = em.createNativeQuery(op.getInverseQuery());
//             // ═══════════════════════════════════════════════════════════════
            
//             // ═══════════════════════════════════════════════════════════════
//             // AFTER (SAFE):
//             // ═══════════════════════════════════════════════════════════════
//             int affected = SecureQueryExecutor.executeNativeQuery(
//                 em, 
//                 op.getInverseQuery(),  // Must be parameterized template
//                 op.getQueryParameters()
//             );
            
//             log.info("Native query compensated: {} rows affected", affected);
//         }
//     }
    
//     public void compensateStoredProcedureSecure(EntityManager em,
//                                                 id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog.OperationLog op) {
        
//         if (op.getInverseProcedure() != null && !op.getInverseProcedure().isEmpty()) {
//             // ═══════════════════════════════════════════════════════════════
//             // SAFE: Validated procedure execution
//             // ═══════════════════════════════════════════════════════════════
//             SecureQueryExecutor.executeProcedure(
//                 em,
//                 op.getInverseProcedure(),  // Validated name
//                 op.getQueryParameters()
//             );
            
//             log.info("Stored procedure compensated");
//         }
//     }
// }

/**
 * SECURITY FIX 4: Query Injection Prevention
 * 
 * PRINCIPLES:
 * 1. ALL queries MUST use parameterized statements
 * 2. Query validation before execution
 * 3. Whitelist-based operation validation
 * 4. No dynamic SQL construction from user input
 */
public class SecureQueryExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(SecureQueryExecutor.class);
    
    // Whitelist of allowed SQL operations for compensation
    private static final Pattern ALLOWED_OPERATIONS = Pattern.compile(
        "^\\s*(UPDATE|INSERT|DELETE|CALL)\\s+.*",
        Pattern.CASE_INSENSITIVE
    );
    
    // Blacklist of dangerous SQL keywords
    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
        ".*(DROP|TRUNCATE|ALTER|CREATE|GRANT|REVOKE)\\s+.*",
        Pattern.CASE_INSENSITIVE
    );
    
    /**
     * Execute parameterized native query safely
     * 
     * @param em EntityManager
     * @param queryTemplate Parameterized query template (e.g., "UPDATE account SET balance = :balance WHERE id = :id")
     * @param parameters Named parameters
     * @return Number of rows affected
     * @throws SecurityException if query is invalid
     */
    public static int executeNativeQuery(EntityManager em, 
                                         String queryTemplate, 
                                         Map<String, Object> parameters) {
        // ═══════════════════════════════════════════════════════════════
        // SECURITY FIX: Validate query before execution
        // ═══════════════════════════════════════════════════════════════
        
        if (queryTemplate == null || queryTemplate.trim().isEmpty()) {
            throw new SecurityException("Query template cannot be null or empty");
        }
        
        // Check if query uses allowed operations
        if (!ALLOWED_OPERATIONS.matcher(queryTemplate).matches()) {
            log.error("Invalid query operation detected: {}", queryTemplate);
            throw new SecurityException(
                "Query must start with UPDATE, INSERT, DELETE, or CALL"
            );
        }
        
        // Check for dangerous keywords
        if (DANGEROUS_KEYWORDS.matcher(queryTemplate).matches()) {
            log.error("Dangerous SQL keyword detected: {}", queryTemplate);
            throw new SecurityException(
                "Query contains dangerous SQL keywords (DROP, TRUNCATE, ALTER, etc.)"
            );
        }
        
        // Verify all parameters are present
        if (!validateParameters(queryTemplate, parameters)) {
            log.error("Query parameter validation failed: {}", queryTemplate);
            throw new SecurityException(
                "All query parameters must be provided"
            );
        }
        
        log.debug("Executing parameterized query: {}", queryTemplate);
        log.debug("Parameters: {}", parameters);
        
        try {
            Query query = em.createNativeQuery(queryTemplate);
            
            // Bind parameters
            if (parameters != null) {
                for (Map.Entry<String, Object> param : parameters.entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            int affected = query.executeUpdate();
            em.flush();
            
            log.info("Query executed successfully - {} rows affected", affected);
            return affected;
            
        } catch (Exception e) {
            log.error("Query execution failed: {}", queryTemplate, e);
            throw new RuntimeException("Failed to execute native query", e);
        }
    }
    
    /**
     * Execute stored procedure safely
     * 
     * @param em EntityManager
     * @param procedureName Procedure name (validated)
     * @param parameters Named parameters
     * @throws SecurityException if procedure name is invalid
     */
    public static void executeProcedure(EntityManager em,
                                        String procedureName,
                                        Map<String, Object> parameters) {
        // ═══════════════════════════════════════════════════════════════
        // SECURITY FIX: Validate procedure name
        // ═══════════════════════════════════════════════════════════════
        
        if (procedureName == null || procedureName.trim().isEmpty()) {
            throw new SecurityException("Procedure name cannot be null or empty");
        }
        
        // Procedure name must be alphanumeric with underscores only
        if (!procedureName.matches("^[a-zA-Z0-9_]+$")) {
            log.error("Invalid procedure name: {}", procedureName);
            throw new SecurityException(
                "Procedure name can only contain letters, numbers, and underscores"
            );
        }
        
        log.debug("Executing procedure: {}", procedureName);
        log.debug("Parameters: {}", parameters);
        
        try {
            Query query = em.createNativeQuery("CALL " + procedureName);
            
            // Bind parameters
            if (parameters != null) {
                for (Map.Entry<String, Object> param : parameters.entrySet()) {
                    query.setParameter(param.getKey(), param.getValue());
                }
            }
            
            query.executeUpdate();
            em.flush();
            
            log.info("Procedure executed successfully: {}", procedureName);
            
        } catch (Exception e) {
            log.error("Procedure execution failed: {}", procedureName, e);
            throw new RuntimeException("Failed to execute stored procedure", e);
        }
    }
    
    /**
     * Validate that all query parameters are provided
     */
    private static boolean validateParameters(String queryTemplate, 
                                              Map<String, Object> parameters) {
        if (queryTemplate == null) {
            return false;
        }
        
        // Find all named parameters in query (e.g., :paramName)
        java.util.regex.Matcher matcher = Pattern.compile(":(\\w+)")
            .matcher(queryTemplate);
        
        while (matcher.find()) {
            String paramName = matcher.group(1);
            if (parameters == null || !parameters.containsKey(paramName)) {
                log.error("Missing parameter: {}", paramName);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Sanitize identifier (table/column name)
     * Use ONLY when absolutely necessary - prefer parameterized queries
     */
    public static String sanitizeIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new SecurityException("Identifier cannot be null or empty");
        }
        
        // Allow only alphanumeric and underscore
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            log.error("Invalid identifier: {}", identifier);
            throw new SecurityException(
                "Identifier can only contain letters, numbers, and underscores"
            );
        }
        
        // Max length check
        if (identifier.length() > 64) {
            throw new SecurityException("Identifier too long (max 64 characters)");
        }
        
        return identifier;
    }
}