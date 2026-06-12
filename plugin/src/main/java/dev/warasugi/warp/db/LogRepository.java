package dev.warasugi.warp.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class LogRepository {
    private final Connection conn;

    public record LogEntry(long id, long ts, String level, String logger, String message) {}

    public LogRepository(Connection conn) {
        this.conn = conn;
    }

    public void insert(long ts, String level, String logger, String message) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO logs(ts,level,logger,message) VALUES(?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, level);
                ps.setString(3, logger);
                ps.setString(4, message);
                ps.executeUpdate();
            }
        }
    }

    public List<LogEntry> query(String level, String q, int pageSize, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id,ts,level,logger,message FROM logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (level != null && !level.isBlank()) {
            sql.append(" AND level=?");
            params.add(level);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND message LIKE ?");
            params.add("%" + q + "%");
        }
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(offset);
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                List<LogEntry> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new LogEntry(rs.getLong("id"), rs.getLong("ts"),
                                rs.getString("level"), rs.getString("logger"), rs.getString("message")));
                    }
                }
                return result;
            }
        }
    }

    public void pruneToMax(int maxRows) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY ts DESC LIMIT ?)")) {
                ps.setInt(1, maxRows);
                ps.executeUpdate();
            }
        }
    }
}
