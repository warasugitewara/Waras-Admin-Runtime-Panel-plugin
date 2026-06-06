package dev.warasugi.warp.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class AuditRepository {
    private final Connection conn;

    public record AuditEntry(long id, long ts, String sourceIp, String action, String detail) {}

    public AuditRepository(Connection conn) {
        this.conn = conn;
    }

    public void insert(long ts, String sourceIp, String action, String detail) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit(ts,source_ip,action,detail) VALUES(?,?,?,?)")) {
            ps.setLong(1, ts);
            ps.setString(2, sourceIp);
            ps.setString(3, action);
            ps.setString(4, detail);
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> query(int pageSize, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,ts,source_ip,action,detail FROM audit ORDER BY ts DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, pageSize);
            ps.setInt(2, offset);
            List<AuditEntry> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new AuditEntry(rs.getLong("id"), rs.getLong("ts"),
                            rs.getString("source_ip"), rs.getString("action"), rs.getString("detail")));
                }
            }
            return result;
        }
    }
}
