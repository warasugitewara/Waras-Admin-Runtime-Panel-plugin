package dev.warasugi.warp.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ChatRepository {
    private final Connection conn;

    public record ChatEntry(long id, long ts, String playerUuid, String playerName, String message) {}

    public ChatRepository(Connection conn) {
        this.conn = conn;
    }

    public void insert(long ts, String uuid, String name, String message) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chat(ts,player_uuid,player_name,message) VALUES(?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, uuid);
                ps.setString(3, name);
                ps.setString(4, message);
                ps.executeUpdate();
            }
        }
    }

    public List<ChatEntry> query(Long since, int pageSize, int offset) throws SQLException {
        String sql = since != null
                ? "SELECT id,ts,player_uuid,player_name,message FROM chat WHERE ts>? ORDER BY ts DESC LIMIT ? OFFSET ?"
                : "SELECT id,ts,player_uuid,player_name,message FROM chat ORDER BY ts DESC LIMIT ? OFFSET ?";
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (since != null) {
                    ps.setLong(1, since);
                    ps.setInt(2, pageSize);
                    ps.setInt(3, offset);
                } else {
                    ps.setInt(1, pageSize);
                    ps.setInt(2, offset);
                }
                List<ChatEntry> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ChatEntry(rs.getLong("id"), rs.getLong("ts"),
                                rs.getString("player_uuid"), rs.getString("player_name"), rs.getString("message")));
                    }
                }
                return result;
            }
        }
    }

    public void pruneToMax(int maxRows) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chat WHERE id NOT IN (SELECT id FROM chat ORDER BY ts DESC LIMIT ?)")) {
                ps.setInt(1, maxRows);
                ps.executeUpdate();
            }
        }
    }
}
