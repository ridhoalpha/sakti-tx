package id.go.kemenkeu.djpbn.sakti.tx.starter.aspect;

import id.go.kemenkeu.djpbn.sakti.tx.starter.config.SaktiTxProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Arrays;

/**
 * Audit Logging untuk Admin API
 * 
 * Mencatat semua aktivitas admin untuk compliance & security
 * 
 * LOG FORMAT:
 * [AUDIT] user=admin | ip=192.168.1.100 | action=retryTransaction | 
 *         args=[txId-123] | status=SUCCESS | timestamp=2025-01-22T10:30:00Z
 */
@Aspect
@Component
@ConditionalOnProperty(prefix = "sakti.tx.security", name = "audit-logging-enabled", havingValue = "true", matchIfMissing = true)
public class AdminApiAuditAspect {
    
    private static final Logger auditLog = LoggerFactory.getLogger("SAKTI_AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AdminApiAuditAspect.class);
    
    private final SaktiTxProperties properties;
    
    public AdminApiAuditAspect(SaktiTxProperties properties) {
        this.properties = properties;
        log.info("Admin API Audit Logging - ENABLED");
    }
    
    /**
     * Log BEFORE admin action
     */
    @Before("execution(* id.go.kemenkeu.djpbn.sakti.tx.starter.admin.TransactionAdminController.*(..))")
    public void logBefore(JoinPoint joinPoint) {
        String username = getUsername();
        String ipAddress = getClientIpAddress();
        String action = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();
        
        auditLog.info("[AUDIT] user={} | ip={} | action={} | args={} | status=STARTED | timestamp={}", 
            username, ipAddress, action, maskSensitiveArgs(args), Instant.now());
    }
    
    /**
     * Log AFTER successful admin action
     */
    @AfterReturning(
        pointcut = "execution(* id.go.kemenkeu.djpbn.sakti.tx.starter.admin.TransactionAdminController.*(..))",
        returning = "result"
    )
    public void logAfterSuccess(JoinPoint joinPoint, Object result) {
        String username = getUsername();
        String ipAddress = getClientIpAddress();
        String action = joinPoint.getSignature().getName();
        
        auditLog.info("[AUDIT] user={} | ip={} | action={} | status=SUCCESS | timestamp={}", 
            username, ipAddress, action, Instant.now());
    }
    
    /**
     * Log AFTER failed admin action
     */
    @AfterThrowing(
        pointcut = "execution(* id.go.kemenkeu.djpbn.sakti.tx.starter.admin.TransactionAdminController.*(..))",
        throwing = "error"
    )
    public void logAfterFailure(JoinPoint joinPoint, Throwable error) {
        String username = getUsername();
        String ipAddress = getClientIpAddress();
        String action = joinPoint.getSignature().getName();
        
        auditLog.error("[AUDIT] user={} | ip={} | action={} | status=FAILED | error={} | timestamp={}", 
            username, ipAddress, action, error.getMessage(), Instant.now());
    }
    
    /**
     * Get authenticated username
     */
    private String getUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception e) {
            log.debug("Cannot get username: {}", e.getMessage());
        }
        return "ANONYMOUS";
    }
    
    /**
     * Get client IP address
     */
    private String getClientIpAddress() {
        try {
            ServletRequestAttributes attributes = 
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attributes.getRequest();
            
            // Check for proxy headers
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }
            
            // Handle multiple IPs (take first one)
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            
            return ip;
            
        } catch (Exception e) {
            log.debug("Cannot get IP address: {}", e.getMessage());
            return "UNKNOWN";
        }
    }
    
    /**
     * Mask sensitive arguments (transaction IDs, etc.)
     */
    private String maskSensitiveArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        
        // For now, just convert to string
        // TODO: Implement proper masking for sensitive data
        return Arrays.toString(args);
    }
}