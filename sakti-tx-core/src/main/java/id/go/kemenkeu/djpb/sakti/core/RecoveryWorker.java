package id.go.kemenkeu.djpbn.sakti.core;

import java.util.Timer;
import java.util.TimerTask;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class RecoveryWorker {
    private final DataSource mainDataSource;
    private final long intervalMs;
    private Timer timer;

    public RecoveryWorker(DataSource mainDataSource, long intervalMs) {
        this.mainDataSource = mainDataSource; this.intervalMs = intervalMs;
    }
    public void start() {
        timer = new Timer("sakti-recovery", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() { scanAndRecover(); }
        }, intervalMs, intervalMs);
    }
    public void stop() {
        if (timer != null) timer.cancel();
    }
    private void scanAndRecover() {
        try (Connection c = mainDataSource.getConnection()) {
            String sql = "SELECT tx_id, status FROM tx_log WHERE status='PENDING'";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> pending = new ArrayList<>();
                    while (rs.next()) pending.add(rs.getString(1));
                    for (String tx : pending) {
                        try (PreparedStatement up = c.prepareStatement("UPDATE tx_log SET status='FAILED', last_update=? WHERE tx_id=?")) {
                            up.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis())); up.setString(2, tx); up.executeUpdate();
                        }
                    }
                }
            }
        } catch (Exception e) { /* log omitted for brevity */ }
    }
}
