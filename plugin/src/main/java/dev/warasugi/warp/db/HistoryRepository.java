package dev.warasugi.warp.db;

import io.javalin.openapi.OpenApiNullable;
import io.javalin.openapi.OpenApiRequired;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HistoryRepository {
    private final Connection conn;

    public record HistoryEntry(long id, long ts, @OpenApiRequired String playerUuid, @OpenApiRequired String playerName,
                               @OpenApiRequired String eventType, @OpenApiRequired @OpenApiNullable String world,
                               double x, double y, double z) {}

    public HistoryRepository(Connection conn) {
        this.conn = conn;
    }

    public void insert(long ts, String uuid, String name, String eventType,
                       String world, double x, double y, double z) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_history(ts,player_uuid,player_name,event_type,world,x,y,z) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, uuid);
                ps.setString(3, name);
                ps.setString(4, eventType);
                ps.setString(5, world);
                ps.setDouble(6, x);
                ps.setDouble(7, y);
                ps.setDouble(8, z);
                ps.executeUpdate();
            }
        }
    }

    public List<HistoryEntry> queryByPlayer(String uuid, int pageSize, int offset) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM player_history WHERE player_uuid=? ORDER BY ts DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid);
                ps.setInt(2, pageSize);
                ps.setInt(3, offset);
                List<HistoryEntry> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new HistoryEntry(
                                rs.getLong("id"), rs.getLong("ts"),
                                rs.getString("player_uuid"), rs.getString("player_name"),
                                rs.getString("event_type"), rs.getString("world"),
                                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")));
                    }
                }
                return result;
            }
        }
    }

    public void pruneToMax(int maxRows) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM player_history WHERE id NOT IN (SELECT id FROM player_history ORDER BY ts DESC LIMIT ?)")) {
                ps.setInt(1, maxRows);
                ps.executeUpdate();
            }
        }
    }
}
