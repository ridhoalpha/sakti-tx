package id.go.kemenkeu.djpbn.sakti.core;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;

public class TxLogRepository {
    private final DataSource mainDataSource;
    public TxLogRepository(DataSource mainDataSource) { this.mainDataSource = mainDataSource; }

    public String createPending() throws Exception {
        String txId = UUID.randomUUID().toString();
        try (Connection c = mainDataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO tx_log(tx_id, status, created_at) VALUES (?, ?, ?)")) {
                ps.setString(1, txId); ps.setString(2, "PENDING"); ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                ps.executeUpdate();
            }
        }
        return txId;
    }

    public void markCommitted(String txId) throws Exception {
        try (Connection c = mainDataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE tx_log SET status=?, last_update=? WHERE tx_id=?")) {
                ps.setString(1, "COMMITTED"); ps.setTimestamp(2, new Timestamp(System.currentTimeMillis())); ps.setString(3, txId); ps.executeUpdate();
            }
        }
    }

    public void markFailed(String txId, String reason) throws Exception {
        try (Connection c = mainDataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE tx_log SET status=?, payload=?, last_update=? WHERE tx_id=?")) {
                ps.setString(1, "FAILED"); ps.setString(2, reason); ps.setTimestamp(3, new Timestamp(System.currentTimeMillis())); ps.setString(4, txId); ps.executeUpdate();
            }
        }
    }
}
