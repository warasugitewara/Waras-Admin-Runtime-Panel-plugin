package dev.warasugi.warp.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws SQLException {
        db = new DatabaseManager(":memory:");
    }

    @Test
    void logRepository_insertAndQuery() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        repo.insert(1000L, "INFO", "Server", "test message");
        var results = repo.query(null, null, 100, 0);
        assertEquals(1, results.size());
        assertEquals("INFO", results.get(0).level());
        assertEquals("test message", results.get(0).message());
    }

    @Test
    void logRepository_queryByLevel() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        repo.insert(1000L, "INFO", "Server", "info msg");
        repo.insert(2000L, "WARN", "Server", "warn msg");
        var results = repo.query("WARN", null, 100, 0);
        assertEquals(1, results.size());
        assertEquals("WARN", results.get(0).level());
    }

    @Test
    void logRepository_pruneToMax() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        for (int i = 0; i < 5; i++) repo.insert(i * 1000L, "INFO", "Server", "msg " + i);
        repo.pruneToMax(3);
        assertEquals(3, repo.query(null, null, 100, 0).size());
    }

    @Test
    void chatRepository_insertAndQuery() throws SQLException {
        var repo = new ChatRepository(db.getConnection());
        repo.insert(1000L, "uuid-1", "Steve", "hello");
        var results = repo.query(null, 100, 0);
        assertEquals(1, results.size());
        assertEquals("Steve", results.get(0).playerName());
    }

    @Test
    void historyRepository_queryByPlayer() throws SQLException {
        var repo = new HistoryRepository(db.getConnection());
        repo.insert(1000L, "uuid-1", "Steve", "join", "world", 0, 64, 0);
        repo.insert(2000L, "uuid-2", "Alex", "join", "world", 0, 64, 0);
        assertEquals(1, repo.queryByPlayer("uuid-1", 100, 0).size());
    }

    @Test
    void auditRepository_insert() throws SQLException {
        var repo = new AuditRepository(db.getConnection());
        repo.insert(1000L, "127.0.0.1", "ban", "{\"player\":\"Steve\"}");
        assertEquals(1, repo.query(100, 0).size());
    }

    @Test
    void logRepository_queryByKeyword() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        repo.insert(1000L, "INFO", "Server", "Hello World");
        repo.insert(2000L, "INFO", "Server", "Goodbye World");
        var results = repo.query(null, "Hello", 100, 0);
        assertEquals(1, results.size());
        assertEquals("Hello World", results.get(0).message());
    }
}
