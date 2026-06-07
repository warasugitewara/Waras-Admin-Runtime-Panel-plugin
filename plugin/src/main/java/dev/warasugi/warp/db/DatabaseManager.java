package dev.warasugi.warp.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseManager {
    private final Connection connection;

    public DatabaseManager(String jdbcPath) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + jdbcPath);
        connection.setAutoCommit(true);
        try (var st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
        }
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (var st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    level TEXT NOT NULL,
                    logger TEXT NOT NULL,
                    message TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_logs_ts ON logs(ts DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS chat (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    message TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chat_ts ON chat(ts DESC)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS player_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    world TEXT,
                    x REAL,
                    y REAL,
                    z REAL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_history_ts ON player_history(ts DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_history_uuid ON player_history(player_uuid)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    source_ip TEXT NOT NULL,
                    action TEXT NOT NULL,
                    detail TEXT
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit(ts DESC)");
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (!connection.isClosed()) connection.close();
    }
}
