package id.go.kemenkeu.djpbn.sakti.tx.core.inspection;

import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContext;
import id.go.kemenkeu.djpbn.sakti.tx.core.context.SaktiTxContextHolder;
import id.go.kemenkeu.djpbn.sakti.tx.core.risk.RiskFlag;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hibernate StatementInspector to detect native SQL operations
 * Flags native queries as risks without blocking them
 */
public class SaktiTxStatementInspector implements StatementInspector {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxStatementInspector.class);
    
    @Override
    public String inspect(String sql) {
        SaktiTxContext context = SaktiTxContextHolder.get();
        
        if (context != null && context.isActive()) {
            String upperSql = sql.trim().toUpperCase();
            
            // Detect DML operations
            if (upperSql.startsWith("UPDATE")) {
                flagRisk(context, RiskFlag.NATIVE_SQL, sql);
                
                // Check if bulk update
                if (isBulkOperation(upperSql)) {
                    flagRisk(context, RiskFlag.BULK_UPDATE, sql);
                }
                
            } else if (upperSql.startsWith("DELETE")) {
                flagRisk(context, RiskFlag.NATIVE_SQL, sql);
                
                if (isBulkOperation(upperSql)) {
                    flagRisk(context, RiskFlag.BULK_DELETE, sql);
                }
                
            } else if (upperSql.startsWith("INSERT")) {
                flagRisk(context, RiskFlag.NATIVE_SQL, sql);
                
            } else if (upperSql.startsWith("CALL") || upperSql.contains("EXECUTE")) {
                flagRisk(context, RiskFlag.STORED_PROCEDURE, sql);
            }
        }
        
        // Return SQL unchanged
        return sql;
    }
    
    private void flagRisk(SaktiTxContext context, RiskFlag flag, String sql) {
        SaktiTxContext updated = context.withRiskFlag(flag);
        SaktiTxContextHolder.update(updated);
        
        log.warn("Risk detected [{}] in txId: {} - SQL: {}", 
            flag, context.getTxId(), truncate(sql, 100));
    }
    
    private boolean isBulkOperation(String sql) {
        // Simple heuristic: bulk if WHERE clause present (not single record)
        return sql.contains("WHERE") && !sql.contains("LIMIT 1");
    }
    
    private String truncate(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }
}