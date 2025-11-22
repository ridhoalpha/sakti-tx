package id.go.kemenkeu.djpbn.sakti.tx.core.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MultiDbTransactionManager {
    
    private static final Logger log = LoggerFactory.getLogger(MultiDbTransactionManager.class);
    
    private final List<RollbackAction> rollbackActions = new ArrayList<>();
    
    public void registerRollback(RollbackAction action) {
        rollbackActions.add(action);
    }
    
    public void executeRollback() {
        log.warn("Executing compensating rollback for {} actions", rollbackActions.size());
        
        for (int i = rollbackActions.size() - 1; i >= 0; i--) {
            RollbackAction action = rollbackActions.get(i);
            try {
                action.rollback();
                log.info("Rollback action {} completed", i);
            } catch (Exception e) {
                log.error("Rollback action {} failed", i, e);
            }
        }
        
        rollbackActions.clear();
    }
    
    public void clear() {
        rollbackActions.clear();
    }
    
    @FunctionalInterface
    public interface RollbackAction {
        void rollback() throws Exception;
    }
}