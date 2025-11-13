package id.go.kemenkeu.djpbn.sakti.core;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class SaktiTransactionManager {
    private final LockManager lockManager;
    private final List<DataSource> dataSources;
    private final boolean useTxLog;
    private final TxLogRepository txLogRepository; // optional

    public SaktiTransactionManager(LockManager lockManager, List<DataSource> dataSources) {
        this(lockManager, dataSources, false, null);
    }

    public SaktiTransactionManager(LockManager lockManager, List<DataSource> dataSources, boolean useTxLog, TxLogRepository txLogRepository) {
        if (dataSources == null || dataSources.isEmpty()) throw new IllegalArgumentException("dataSources required");
        this.lockManager = lockManager; this.dataSources = new ArrayList<>(dataSources);
        this.useTxLog = useTxLog; this.txLogRepository = txLogRepository;
    }

    public <T> T execute(String lockKey, long waitMillis, long leaseMillis, Callable<T> callable) throws Exception {
        try (LockManager.LockHandle h = lockManager.tryLock(lockKey, waitMillis, leaseMillis)) {
            if (!h.isAcquired()) throw new SaktiException("Could not acquire lock: " + lockKey);
            return executeAcrossDatasources(callable);
        }
    }

    public <T> T executeAcrossDatasources(Callable<T> callable) throws Exception {
        List<Connection> conns = new ArrayList<>(dataSources.size());
        String txId = null;
        try {
            if (useTxLog && txLogRepository != null) {
                txId = txLogRepository.createPending();
            }
            for (DataSource ds : dataSources) {
                Connection c = ds.getConnection(); c.setAutoCommit(false); conns.add(c);
            }
            T res = callable.call();
            for (Connection c : conns) c.commit();
            if (useTxLog && txLogRepository != null && txId != null) txLogRepository.markCommitted(txId);
            return res;
        } catch (Exception ex) {
            for (Connection c : conns) { try { c.rollback(); } catch (Exception ignored) {} }
            if (useTxLog && txLogRepository != null && txId != null) {
                try { txLogRepository.markFailed(txId, ex.getMessage()); } catch (Exception ignore) {}
            }
            throw ex;
        } finally {
            for (Connection c : conns) { try { c.close(); } catch (Exception ignored) {} }
        }
    }
}
