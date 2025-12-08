package id.go.kemenkeu.djpbn.sakti.tx.starter.admin;

// import id.go.kemenkeu.djpbn.sakti.tx.core.compensate.CompensatingTransactionExecutor;
// import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLog;
// import id.go.kemenkeu.djpbn.sakti.tx.core.log.TransactionLogManager;
// import id.go.kemenkeu.djpbn.sakti.tx.starter.admin.dto.TransactionStatusDto;
// import id.go.kemenkeu.djpbn.sakti.tx.starter.worker.TransactionRecoveryWorker;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.*;

// import java.time.Instant;
// import java.util.HashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.stream.Collectors;

// /**
//  * Admin API untuk transaction management
//  * 
//  * ENDPOINTS:
//  * - GET    /admin/transactions/failed          - List failed transactions
//  * - GET    /admin/transactions/{txId}          - Get transaction details
//  * - POST   /admin/transactions/retry/{txId}    - Manual retry
//  * - DELETE /admin/transactions/{txId}          - Remove from failed queue
//  * - GET    /admin/transactions/metrics         - Recovery metrics
//  * 
//  * SECURITY NOTE:
//  * This controller should be protected with proper authentication/authorization
//  * Consider using Spring Security with admin role requirement
//  */
// @RestController
// @RequestMapping("/admin/transactions")
// @ConditionalOnProperty(prefix = "sakti.tx.multi-db.admin-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionAdminController {
    
    // private static final Logger log = LoggerFactory.getLogger(TransactionAdminController.class);
    
    // private final TransactionLogManager logManager;
    // private final CompensatingTransactionExecutor compensator;
    // private final TransactionRecoveryWorker recoveryWorker;
    
    // public TransactionAdminController(
    //         TransactionLogManager logManager,
    //         CompensatingTransactionExecutor compensator,
    //         TransactionRecoveryWorker recoveryWorker) {
    //     this.logManager = logManager;
    //     this.compensator = compensator;
    //     this.recoveryWorker = recoveryWorker;
    // }
    
    // /**
    //  * GET /admin/transactions/failed
    //  * 
    //  * List all failed transactions yang memerlukan manual intervention
    //  */
    // @GetMapping("/failed")
    // public ResponseEntity<Map<String, Object>> getFailedTransactions() {
    //     log.info("Admin API: Getting failed transactions");
        
    //     try {
    //         List<TransactionLog> failedTxs = logManager.getFailedTransactions();
            
    //         List<TransactionStatusDto> dtos = failedTxs.stream()
    //             .map(TransactionStatusDto::fromTransactionLog)
    //             .collect(Collectors.toList());
            
    //         Map<String, Object> response = new HashMap<>();
    //         response.put("count", dtos.size());
    //         response.put("transactions", dtos);
    //         response.put("timestamp", Instant.now());
            
    //         log.info("Found {} failed transactions", dtos.size());
            
    //         return ResponseEntity.ok(response);
            
    //     } catch (Exception e) {
    //         log.error("Failed to get failed transactions", e);
            
    //         Map<String, Object> error = new HashMap<>();
    //         error.put("error", "Failed to retrieve failed transactions");
    //         error.put("message", e.getMessage());
            
    //         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //     }
    // }
    
    // /**
    //  * GET /admin/transactions/{txId}
    //  * 
    //  * Get detailed information tentang specific transaction
    //  */
    // @GetMapping("/{txId}")
    // public ResponseEntity<Map<String, Object>> getTransaction(@PathVariable String txId) {
    //     log.info("Admin API: Getting transaction details for: {}", txId);
        
    //     try {
    //         TransactionLog txLog = logManager.getLog(txId);
            
    //         if (txLog == null) {
    //             Map<String, Object> error = new HashMap<>();
    //             error.put("error", "Transaction not found");
    //             error.put("txId", txId);
                
    //             return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    //         }
            
    //         TransactionStatusDto dto = TransactionStatusDto.fromTransactionLog(txLog);
            
    //         Map<String, Object> response = new HashMap<>();
    //         response.put("transaction", dto);
    //         response.put("timestamp", Instant.now());
            
    //         return ResponseEntity.ok(response);
            
    //     } catch (Exception e) {
    //         log.error("Failed to get transaction: {}", txId, e);
            
    //         Map<String, Object> error = new HashMap<>();
    //         error.put("error", "Failed to retrieve transaction");
    //         error.put("txId", txId);
    //         error.put("message", e.getMessage());
            
    //         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //     }
    // }
    
    // /**
    //  * POST /admin/transactions/retry/{txId}
    //  * 
    //  * Manual retry untuk failed transaction
    //  * Attempts to rollback the transaction again
    //  */
    // @PostMapping("/retry/{txId}")
    // public ResponseEntity<Map<String, Object>> retryTransaction(@PathVariable String txId) {
    //     log.warn("═══════════════════════════════════════════════════════════");
    //     log.warn("Admin API: Manual retry requested for transaction: {}", txId);
    //     log.warn("═══════════════════════════════════════════════════════════");
        
    //     try {
    //         TransactionLog txLog = logManager.getLog(txId);
            
    //         if (txLog == null) {
    //             Map<String, Object> error = new HashMap<>();
    //             error.put("error", "Transaction not found");
    //             error.put("txId", txId);
    //             error.put("success", false);
                
    //             log.error("Transaction not found: {}", txId);
    //             return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    //         }
            
    //         // Check if already rolled back or committed
    //         if (txLog.getState() == TransactionLog.TransactionState.ROLLED_BACK) {
    //             Map<String, Object> response = new HashMap<>();
    //             response.put("success", true);
    //             response.put("message", "Transaction already rolled back");
    //             response.put("txId", txId);
    //             response.put("state", txLog.getState());
                
    //             return ResponseEntity.ok(response);
    //         }
            
    //         if (txLog.getState() == TransactionLog.TransactionState.COMMITTED) {
    //             Map<String, Object> error = new HashMap<>();
    //             error.put("error", "Cannot retry committed transaction");
    //             error.put("txId", txId);
    //             error.put("success", false);
                
    //             return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    //         }
            
    //         // Attempt rollback
    //         log.info("Attempting manual rollback for txId: {}", txId);
            
    //         logManager.markRollingBack(txId, "Manual retry via admin API");
    //         txLog.incrementRetry();
    //         logManager.saveLog(txLog);
            
    //         try {
    //             compensator.rollback(txLog);
    //             logManager.markRolledBack(txId);
                
    //             log.info("✓ Manual retry SUCCESSFUL for txId: {}", txId);
                
    //             Map<String, Object> response = new HashMap<>();
    //             response.put("success", true);
    //             response.put("message", "Transaction rolled back successfully");
    //             response.put("txId", txId);
    //             response.put("operationsCompensated", txLog.getOperations().size());
    //             response.put("retryCount", txLog.getRetryCount());
    //             response.put("timestamp", Instant.now());
                
    //             return ResponseEntity.ok(response);
                
    //         } catch (Exception rollbackError) {
    //             log.error("✗ Manual retry FAILED for txId: {}", txId, rollbackError);
                
    //             logManager.markFailed(txId, 
    //                 "Manual retry failed: " + rollbackError.getMessage());
                
    //             Map<String, Object> error = new HashMap<>();
    //             error.put("error", "Rollback failed");
    //             error.put("txId", txId);
    //             error.put("message", rollbackError.getMessage());
    //             error.put("success", false);
    //             error.put("retryCount", txLog.getRetryCount());
                
    //             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //         }
            
    //     } catch (Exception e) {
    //         log.error("Failed to retry transaction: {}", txId, e);
            
    //         Map<String, Object> error = new HashMap<>();
    //         error.put("error", "Retry operation failed");
    //         error.put("txId", txId);
    //         error.put("message", e.getMessage());
    //         error.put("success", false);
            
    //         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //     }
    // }
    
    // /**
    //  * DELETE /admin/transactions/{txId}
    //  * 
    //  * Remove transaction dari failed queue
    //  * WARNING: Hanya gunakan jika sudah di-fix manual di database
    //  */
    // @DeleteMapping("/{txId}")
    // public ResponseEntity<Map<String, Object>> removeTransaction(@PathVariable String txId) {
    //     log.warn("═══════════════════════════════════════════════════════════");
    //     log.warn("Admin API: Remove transaction requested: {}", txId);
    //     log.warn("WARNING: This should only be used after manual database fix");
    //     log.warn("═══════════════════════════════════════════════════════════");
        
    //     try {
    //         TransactionLog txLog = logManager.getLog(txId);
            
    //         if (txLog == null) {
    //             Map<String, Object> error = new HashMap<>();
    //             error.put("error", "Transaction not found");
    //             error.put("txId", txId);
                
    //             return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    //         }
            
    //         // Mark as manually resolved
    //         txLog.setState(TransactionLog.TransactionState.ROLLED_BACK);
    //         txLog.addMetadata("manuallyResolved", true);
    //         txLog.addMetadata("resolvedAt", Instant.now().toString());
    //         logManager.saveLog(txLog);
            
    //         log.info("Transaction {} marked as manually resolved", txId);
            
    //         Map<String, Object> response = new HashMap<>();
    //         response.put("success", true);
    //         response.put("message", "Transaction marked as manually resolved");
    //         response.put("txId", txId);
    //         response.put("timestamp", Instant.now());
            
    //         return ResponseEntity.ok(response);
            
    //     } catch (Exception e) {
    //         log.error("Failed to remove transaction: {}", txId, e);
            
    //         Map<String, Object> error = new HashMap<>();
    //         error.put("error", "Failed to remove transaction");
    //         error.put("txId", txId);
    //         error.put("message", e.getMessage());
            
    //         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //     }
    // }
    
    // /**
    //  * GET /admin/transactions/metrics
    //  * 
    //  * Get recovery worker metrics
    //  */
    // @GetMapping("/metrics")
    // public ResponseEntity<Map<String, Object>> getMetrics() {
    //     log.debug("Admin API: Getting recovery metrics");
        
    //     try {
    //         TransactionRecoveryWorker.RecoveryMetrics metrics = recoveryWorker.getMetrics();
            
    //         Map<String, Object> response = new HashMap<>();
    //         response.put("totalAttempts", metrics.totalAttempts);
    //         response.put("successful", metrics.successful);
    //         response.put("failed", metrics.failed);
    //         response.put("successRate", String.format("%.2f%%", metrics.getSuccessRate()));
    //         response.put("lastScanFound", metrics.lastScanFound);
    //         response.put("lastScanTime", metrics.lastScanTime);
    //         response.put("timestamp", Instant.now());
            
    //         return ResponseEntity.ok(response);
            
    //     } catch (Exception e) {
    //         log.error("Failed to get metrics", e);
            
    //         Map<String, Object> error = new HashMap<>();
    //         error.put("error", "Failed to retrieve metrics");
    //         error.put("message", e.getMessage());
            
    //         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //     }
    // }
    
    // /**
    //  * GET /admin/transactions/health
    //  * 
    //  * Simple health check endpoint
    //  */
    // @GetMapping("/health")
    // public ResponseEntity<Map<String, Object>> health() {
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("status", "UP");
    //     response.put("service", "SAKTI Transaction Admin API");
    //     response.put("timestamp", Instant.now());
        
    //     return ResponseEntity.ok(response);
    // }
    
    // /**
    //  * POST /admin/transactions/force-scan
    //  * 
    //  * Force immediate recovery scan (bypass schedule)
    //  */
    // @PostMapping("/force-scan")
    // public ResponseEntity<Map<String, Object>> forceScan() {
    //     log.warn("Admin API: Forcing immediate recovery scan");
        
    //     try {
    //         recoveryWorker.scanAndRecoverStalledTransactions();
            
    //         TransactionRecoveryWorker.RecoveryMetrics metrics = recoveryWorker.getMetrics();
            
    //         Map<String, Object> response = new HashMap<>();
    //         response.put("success", true);
    //         response.put("message", "Recovery scan completed");
    //         response.put("found", metrics.lastScanFound);
    //         response.put("timestamp", Instant.now());
            
    //         return ResponseEntity.ok(response);
            
    //     } catch (Exception e) {
    //         log.error("Failed to force scan", e);
            
    //         Map<String, Object> error = new HashMap<>();
    //         error.put("error", "Failed to force scan");
    //         error.put("message", e.getMessage());
            
    //         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    //     }
    // }
}