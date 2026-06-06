# WPanel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Paper 1.21.x Minecraft サーバー向け Web 管理パネルプラグイン WPanel を実装する。

**Architecture:** Paper プラグインが Javalin 6（127.0.0.1:8080）を組み込む。SQLite が logs/chat/history/audit を保持し BAN は Paper BanList を SoT とする。フロントエンドは React+TS SPA で JAR に同梱。Cloudflare Tunnel で外部公開。

**Tech Stack:** Java 21, Paper API 1.21.1, Javalin 6.4.0, jjwt 0.12.6, samstevens/totp 1.7.1, xerial/sqlite-jdbc 3.46.1.3, Javalin OpenAPI plugin, React 18 + TS 5 + Vite 5 + Tailwind v3 + ApexCharts + xterm.js 5, openapi-typescript

---

## ディレクトリマップ

```
(repo root)/
├── plugin/                              ← Gradle プロジェクト
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/
│       ├── main/java/dev/warasugi/wpanel/
│       │   ├── WPanelPlugin.java
│       │   ├── config/PanelConfig.java
│       │   ├── db/
│       │   │   ├── DatabaseManager.java
│       │   │   ├── LogRepository.java
│       │   │   ├── ChatRepository.java
│       │   │   ├── HistoryRepository.java
│       │   │   └── AuditRepository.java
│       │   ├── auth/
│       │   │   ├── TotpManager.java
│       │   │   ├── JwtManager.java
│       │   │   └── RateLimiter.java
│       │   ├── web/
│       │   │   ├── WebServer.java
│       │   │   ├── middleware/AuthMiddleware.java
│       │   │   ├── middleware/CsrfMiddleware.java
│       │   │   └── handlers/
│       │   │       ├── AuthHandler.java
│       │   │       ├── StatusHandler.java
│       │   │       ├── PlayerHandler.java
│       │   │       ├── BanHandler.java
│       │   │       ├── ChatHandler.java
│       │   │       ├── ConsoleHandler.java
│       │   │       └── LogHandler.java
│       │   ├── ws/AdminWsHandler.java
│       │   ├── metrics/MetricsCollector.java
│       │   ├── console/WebSocketAppender.java
│       │   └── commands/WPanelCommand.java
│       ├── main/resources/
│       │   ├── plugin.yml
│       │   └── config.yml
│       └── test/java/dev/warasugi/wpanel/
│           ├── auth/TotpManagerTest.java
│           ├── auth/JwtManagerTest.java
│           ├── auth/RateLimiterTest.java
│           └── db/RepositoryTest.java
└── frontend/                            ← Vite プロジェクト
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.ts
    └── src/
        ├── lib/api.ts
        ├── lib/api-types.ts            ← openapi-typescript 自動生成（手書き禁止）
        ├── lib/ws.ts
        ├── pages/{Login,Dashboard,Terminal,Players,Chat,Bans,Logs,History}.tsx
        └── components/{charts/,layout/Sidebar.tsx}
```

---

## Task 1: Gradle プロジェクト scaffold

**Files:**
- Create: `plugin/settings.gradle.kts`
- Create: `plugin/build.gradle.kts`
- Create: `plugin/src/main/resources/plugin.yml`
- Create: `plugin/src/main/resources/config.yml`

- [ ] **Step 1: settings.gradle.kts を作成**

```kotlin
// plugin/settings.gradle.kts
rootProject.name = "wpanel"
```

- [ ] **Step 2: build.gradle.kts を作成**

```kotlin
// plugin/build.gradle.kts
plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.3"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("io.javalin:javalin:6.4.0")
    // NOTE: OpenAPI plugin artifact を要確認 → https://github.com/javalin/javalin-openapi
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:6.1.3")
    annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:6.1.3")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("dev.samstevens.totp:totp:1.7.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    relocate("io.javalin", "dev.warasugi.wpanel.libs.javalin")
    relocate("io.jsonwebtoken", "dev.warasugi.wpanel.libs.jwt")
    relocate("com.fasterxml.jackson", "dev.warasugi.wpanel.libs.jackson")
    mergeServiceFiles()
}

tasks.test { useJUnitPlatform() }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
```

- [ ] **Step 3: Gradle wrapper 生成**

```powershell
cd plugin
gradle wrapper --gradle-version 8.10.2
```

- [ ] **Step 4: plugin.yml を作成**

```yaml
# plugin/src/main/resources/plugin.yml
name: WPanel
version: "1.0.0"
main: dev.warasugi.wpanel.WPanelPlugin
api-version: "1.21"
description: Web管理パネルプラグイン
authors: [warasugi]
commands:
  wpanel:
    description: WPanel管理コマンド
    permission: wpanel.admin
    usage: /wpanel <setup|status|reload|token>
permissions:
  wpanel.admin:
    description: WPanel管理者権限
    default: op
```

- [ ] **Step 5: config.yml を作成**

```yaml
# plugin/src/main/resources/config.yml
server:
  host: "127.0.0.1"
  port: 8080
  cors-origins:
    - "https://admin.warasugi.com"

auth:
  totp-issuer: "WPanel"
  totp-secret: ""
  login-max-attempts: 5
  login-lockout-minutes: 10
  session-hours: 8

storage:
  logs-max-rows: 100000
  chat-max-rows: 50000
  history-max-rows: 50000

console:
  command-blocklist:
    - "stop"
    - "restart"
```

- [ ] **Step 6: ビルド確認**

```powershell
cd plugin
.\gradlew.bat compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: commit**

```bash
git add plugin/
git commit -m "chore: Gradleプロジェクト scaffold"
```

---

## Task 2: PanelConfig

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/config/PanelConfig.java`

- [ ] **Step 1: PanelConfig.java を作成**

```java
package dev.warasugi.wpanel.config;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;

public class PanelConfig {
    private final String host;
    private final int port;
    private final List<String> corsOrigins;
    private final String totpIssuer;
    private String totpSecret;
    private final int loginMaxAttempts;
    private final long loginLockoutMs;
    private final int sessionHours;
    private final int logsMaxRows;
    private final int chatMaxRows;
    private final int historyMaxRows;
    private final List<String> commandBlocklist;

    public PanelConfig(FileConfiguration cfg) {
        host             = cfg.getString("server.host", "127.0.0.1");
        port             = cfg.getInt("server.port", 8080);
        corsOrigins      = cfg.getStringList("server.cors-origins");
        totpIssuer       = cfg.getString("auth.totp-issuer", "WPanel");
        totpSecret       = cfg.getString("auth.totp-secret", "");
        loginMaxAttempts = cfg.getInt("auth.login-max-attempts", 5);
        loginLockoutMs   = cfg.getLong("auth.login-lockout-minutes", 10) * 60_000L;
        sessionHours     = cfg.getInt("auth.session-hours", 8);
        logsMaxRows      = cfg.getInt("storage.logs-max-rows", 100_000);
        chatMaxRows      = cfg.getInt("storage.chat-max-rows", 50_000);
        historyMaxRows   = cfg.getInt("storage.history-max-rows", 50_000);
        commandBlocklist = cfg.getStringList("console.command-blocklist");
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public List<String> getCorsOrigins() { return corsOrigins; }
    public String getTotpIssuer() { return totpIssuer; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String s) { totpSecret = s; }
    public int getLoginMaxAttempts() { return loginMaxAttempts; }
    public long getLoginLockoutMs() { return loginLockoutMs; }
    public int getSessionHours() { return sessionHours; }
    public int getLogsMaxRows() { return logsMaxRows; }
    public int getChatMaxRows() { return chatMaxRows; }
    public int getHistoryMaxRows() { return historyMaxRows; }
    public List<String> getCommandBlocklist() { return commandBlocklist; }
}
```

- [ ] **Step 2: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/config/
git commit -m "feat: PanelConfig — config.yml のロード"
```

---

## Task 3: TotpManager + テスト

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/auth/TotpManager.java`
- Create: `plugin/src/test/java/dev/warasugi/wpanel/auth/TotpManagerTest.java`

- [ ] **Step 1: テストを書く**

```java
// plugin/src/test/java/dev/warasugi/wpanel/auth/TotpManagerTest.java
package dev.warasugi.wpanel.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotpManagerTest {

    @Test
    void generateSecret_returnsNonBlankString() {
        String secret = TotpManager.generateSecret();
        assertFalse(secret.isBlank());
        assertTrue(secret.length() >= 16);
    }

    @Test
    void verify_withInvalidCode_returnsFalse() {
        TotpManager m = new TotpManager(TotpManager.generateSecret());
        assertFalse(m.verify("000000"));
    }

    @Test
    void getQrUri_containsSecretAndIssuer() {
        String secret = TotpManager.generateSecret();
        TotpManager m = new TotpManager(secret);
        String uri = m.getQrUri("WPanel");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains(secret));
        assertTrue(uri.contains("WPanel"));
    }

    @Test
    void getSecret_returnsConstructorValue() {
        String secret = TotpManager.generateSecret();
        assertEquals(secret, new TotpManager(secret).getSecret());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```powershell
cd plugin
.\gradlew.bat test --tests "dev.warasugi.wpanel.auth.TotpManagerTest"
```
Expected: FAIL (クラスが存在しないためコンパイルエラー)

- [ ] **Step 3: TotpManager.java を実装**

```java
package dev.warasugi.wpanel.auth;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;

public class TotpManager {
    private final String secret;
    private final CodeVerifier verifier;

    public TotpManager(String secret) {
        this.secret = secret;
        this.verifier = new DefaultCodeVerifier(
            new DefaultCodeGenerator(),
            new SystemTimeProvider()
        );
    }

    public static String generateSecret() {
        return new DefaultSecretGenerator().generate();
    }

    public boolean verify(String code) {
        return verifier.isValidCode(secret, code);
    }

    public String getQrUri(String issuer) {
        return "otpauth://totp/" + issuer + "?secret=" + secret + "&issuer=" + issuer;
    }

    public String getSecret() { return secret; }
}
```

- [ ] **Step 4: テストが通ることを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.auth.TotpManagerTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 5: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/auth/TotpManager.java \
        plugin/src/test/java/dev/warasugi/wpanel/auth/TotpManagerTest.java
git commit -m "feat: TotpManager — TOTP生成・検証"
```

---

## Task 4: JwtManager + テスト

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/auth/JwtManager.java`
- Create: `plugin/src/test/java/dev/warasugi/wpanel/auth/JwtManagerTest.java`

- [ ] **Step 1: テストを書く**

```java
// plugin/src/test/java/dev/warasugi/wpanel/auth/JwtManagerTest.java
package dev.warasugi.wpanel.auth;

import io.jsonwebtoken.Jwts;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtManagerTest {

    private static SecretKey testKey() {
        return Jwts.SIG.HS256.key().build();
    }

    @Test
    void issue_thenIsValid_returnsTrue() {
        JwtManager jwt = new JwtManager(testKey(), 8 * 3600_000L);
        assertTrue(jwt.isValid(jwt.issue()));
    }

    @Test
    void isValid_withExpiredToken_returnsFalse() {
        JwtManager jwt = new JwtManager(testKey(), -1L);
        assertFalse(jwt.isValid(jwt.issue()));
    }

    @Test
    void isValid_withGarbage_returnsFalse() {
        JwtManager jwt = new JwtManager(testKey(), 3600_000L);
        assertFalse(jwt.isValid("garbage.token.here"));
    }

    @Test
    void isValid_withDifferentKey_returnsFalse() {
        String token = new JwtManager(testKey(), 3600_000L).issue();
        assertFalse(new JwtManager(testKey(), 3600_000L).isValid(token));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.auth.JwtManagerTest"
```
Expected: FAIL

- [ ] **Step 3: JwtManager.java を実装**

```java
package dev.warasugi.wpanel.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;

public class JwtManager {
    private final SecretKey key;
    private final long expirationMs;

    /** ファイルから鍵を読む（なければ生成して保存） */
    public static JwtManager fromFile(Path keyFile, long expirationMs) throws Exception {
        SecretKey key;
        if (Files.exists(keyFile)) {
            byte[] bytes = Base64.getDecoder().decode(Files.readString(keyFile).strip());
            key = Keys.hmacShaKeyFor(bytes);
        } else {
            key = Jwts.SIG.HS256.key().build();
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()));
        }
        return new JwtManager(key, expirationMs);
    }

    /** テスト用 */
    JwtManager(SecretKey key, long expirationMs) {
        this.key = key;
        this.expirationMs = expirationMs;
    }

    public String issue() {
        Date now = new Date();
        return Jwts.builder()
            .issuedAt(now)
            .expiration(new Date(now.getTime() + expirationMs))
            .signWith(key)
            .compact();
    }

    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.auth.JwtManagerTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 5: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/auth/JwtManager.java \
        plugin/src/test/java/dev/warasugi/wpanel/auth/JwtManagerTest.java
git commit -m "feat: JwtManager — JWT発行・検証"
```

---

## Task 5: RateLimiter + テスト

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/auth/RateLimiter.java`
- Create: `plugin/src/test/java/dev/warasugi/wpanel/auth/RateLimiterTest.java`

- [ ] **Step 1: テストを書く**

```java
// plugin/src/test/java/dev/warasugi/wpanel/auth/RateLimiterTest.java
package dev.warasugi.wpanel.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsUpToMaxAttempts() {
        RateLimiter r = new RateLimiter(3, 60_000);
        assertTrue(r.isAllowed("1.2.3.4"));
        assertTrue(r.isAllowed("1.2.3.4"));
        assertTrue(r.isAllowed("1.2.3.4"));
        assertFalse(r.isAllowed("1.2.3.4"));
    }

    @Test
    void differentIps_areIndependent() {
        RateLimiter r = new RateLimiter(1, 60_000);
        assertTrue(r.isAllowed("1.1.1.1"));
        assertFalse(r.isAllowed("1.1.1.1"));
        assertTrue(r.isAllowed("2.2.2.2"));
    }

    @Test
    void reset_clearsAttempts() {
        RateLimiter r = new RateLimiter(1, 60_000);
        r.isAllowed("1.2.3.4");
        assertFalse(r.isAllowed("1.2.3.4"));
        r.reset("1.2.3.4");
        assertTrue(r.isAllowed("1.2.3.4"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.auth.RateLimiterTest"
```

- [ ] **Step 3: RateLimiter.java を実装**

```java
package dev.warasugi.wpanel.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimiter {
    private record Window(AtomicInteger count, long start) {}
    private final ConcurrentHashMap<String, Window> map = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long windowMs;

    public RateLimiter(int maxAttempts, long windowMs) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
    }

    public boolean isAllowed(String ip) {
        long now = System.currentTimeMillis();
        Window w = map.compute(ip, (k, v) ->
            (v == null || now - v.start() > windowMs)
                ? new Window(new AtomicInteger(0), now)
                : v
        );
        return w.count().incrementAndGet() <= maxAttempts;
    }

    public void reset(String ip) { map.remove(ip); }
}
```

- [ ] **Step 4: テストが通ることを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.auth.RateLimiterTest"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/auth/RateLimiter.java \
        plugin/src/test/java/dev/warasugi/wpanel/auth/RateLimiterTest.java
git commit -m "feat: RateLimiter — ログイン試行制限"
```

---

## Task 6: DatabaseManager + Repositories + テスト

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/db/DatabaseManager.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/db/LogRepository.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/db/ChatRepository.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/db/HistoryRepository.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/db/AuditRepository.java`
- Create: `plugin/src/test/java/dev/warasugi/wpanel/db/RepositoryTest.java`

- [ ] **Step 1: テストを書く**

```java
// plugin/src/test/java/dev/warasugi/wpanel/db/RepositoryTest.java
package dev.warasugi.wpanel.db;

import org.junit.jupiter.api.*;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws SQLException { db = new DatabaseManager(); } // in-memory

    @AfterEach
    void tearDown() throws SQLException { db.close(); }

    @Test
    void logRepository_insertAndQuery() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        repo.insert(1000L, "INFO", "Server", "Started");
        repo.insert(2000L, "WARN", "Server", "Lag detected");
        List<LogRepository.LogEntry> all = repo.query(null, null, 100, 0);
        assertEquals(2, all.size());
        assertEquals("Lag detected", all.get(0).message()); // DESC
    }

    @Test
    void logRepository_filterByLevel() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        repo.insert(1000L, "INFO", "S", "msg1");
        repo.insert(2000L, "WARN", "S", "msg2");
        assertEquals(1, repo.query("WARN", null, 100, 0).size());
    }

    @Test
    void logRepository_filterByKeyword() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        repo.insert(1000L, "INFO", "S", "Player joined");
        repo.insert(2000L, "INFO", "S", "Server tick");
        assertEquals(1, repo.query(null, "joined", 100, 0).size());
    }

    @Test
    void logRepository_pruneToMax() throws SQLException {
        var repo = new LogRepository(db.getConnection());
        for (int i = 0; i < 10; i++) repo.insert(i * 1000L, "INFO", "S", "msg" + i);
        repo.pruneToMax(5);
        assertEquals(5, repo.query(null, null, 100, 0).size());
    }

    @Test
    void chatRepository_insertAndQuery() throws SQLException {
        var repo = new ChatRepository(db.getConnection());
        repo.insert(1000L, "uuid-1", "Steve", "Hello");
        var list = repo.query(null, 100, 0);
        assertEquals(1, list.size());
        assertEquals("Hello", list.get(0).message());
    }

    @Test
    void historyRepository_insertAndQueryByUuid() throws SQLException {
        var repo = new HistoryRepository(db.getConnection());
        repo.insert(1000L, "uuid-1", "Steve", "join", "world", 0, 64, 0);
        repo.insert(2000L, "uuid-2", "Alex",  "join", "world", 0, 64, 0);
        assertEquals(1, repo.queryByPlayer("uuid-1", 100, 0).size());
    }

    @Test
    void auditRepository_insert() throws SQLException {
        var repo = new AuditRepository(db.getConnection());
        repo.insert(1000L, "127.0.0.1", "ban", "{\"player\":\"Steve\"}");
        assertEquals(1, repo.query(100, 0).size());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.db.RepositoryTest"
```

- [ ] **Step 3: DatabaseManager.java を作成**

```java
package dev.warasugi.wpanel.db;

import java.nio.file.Path;
import java.sql.*;

public class DatabaseManager implements AutoCloseable {
    private final Connection conn;

    /** 本番用: ファイルDB */
    public DatabaseManager(Path dbFile) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        init();
    }

    /** テスト用: インメモリDB */
    public DatabaseManager() throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        init();
    }

    private void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts      INTEGER NOT NULL,
                    level   TEXT NOT NULL,
                    logger  TEXT NOT NULL,
                    message TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_logs_ts    ON logs(ts DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS chat (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts          INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    message     TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chat_ts ON chat(ts DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_history (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts          INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    event_type  TEXT NOT NULL,
                    world       TEXT,
                    x REAL, y REAL, z REAL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ph_uuid ON player_history(player_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ph_ts   ON player_history(ts DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS audit (
                    id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts        INTEGER NOT NULL,
                    source_ip TEXT NOT NULL,
                    action    TEXT NOT NULL,
                    detail    TEXT
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit(ts DESC)");
        }
    }

    public Connection getConnection() { return conn; }

    @Override
    public void close() throws SQLException { conn.close(); }
}
```

- [ ] **Step 4: LogRepository.java を作成**

```java
package dev.warasugi.wpanel.db;

import java.sql.*;
import java.util.*;

public class LogRepository {
    private final Connection conn;
    public record LogEntry(long id, long ts, String level, String logger, String message) {}

    public LogRepository(Connection conn) { this.conn = conn; }

    public void insert(long ts, String level, String logger, String message) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO logs(ts,level,logger,message) VALUES(?,?,?,?)")) {
            ps.setLong(1, ts);
            ps.setString(2, level);
            ps.setString(3, logger);
            ps.setString(4, message);
            ps.executeUpdate();
        }
    }

    public List<LogEntry> query(String level, String q, int pageSize, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id,ts,level,logger,message FROM logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (level != null && !level.isBlank()) { sql.append(" AND level=?"); params.add(level); }
        if (q != null && !q.isBlank()) { sql.append(" AND message LIKE ?"); params.add("%" + q + "%"); }
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        params.add(Math.min(pageSize, 500));
        params.add(offset);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            List<LogEntry> result = new ArrayList<>();
            while (rs.next()) result.add(new LogEntry(
                rs.getLong("id"), rs.getLong("ts"), rs.getString("level"),
                rs.getString("logger"), rs.getString("message")));
            return result;
        }
    }

    public void pruneToMax(int maxRows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY ts DESC LIMIT ?)")) {
            ps.setInt(1, maxRows);
            ps.executeUpdate();
        }
    }
}
```

- [ ] **Step 5: ChatRepository.java を作成**

```java
package dev.warasugi.wpanel.db;

import java.sql.*;
import java.util.*;

public class ChatRepository {
    private final Connection conn;
    public record ChatEntry(long id, long ts, String playerUuid, String playerName, String message) {}

    public ChatRepository(Connection conn) { this.conn = conn; }

    public void insert(long ts, String uuid, String name, String message) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chat(ts,player_uuid,player_name,message) VALUES(?,?,?,?)")) {
            ps.setLong(1, ts); ps.setString(2, uuid);
            ps.setString(3, name); ps.setString(4, message);
            ps.executeUpdate();
        }
    }

    public List<ChatEntry> query(Long since, int pageSize, int offset) throws SQLException {
        String sql = since != null
            ? "SELECT id,ts,player_uuid,player_name,message FROM chat WHERE ts>? ORDER BY ts DESC LIMIT ? OFFSET ?"
            : "SELECT id,ts,player_uuid,player_name,message FROM chat ORDER BY ts DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (since != null) { ps.setLong(1, since); ps.setInt(2, Math.min(pageSize, 500)); ps.setInt(3, offset); }
            else { ps.setInt(1, Math.min(pageSize, 500)); ps.setInt(2, offset); }
            ResultSet rs = ps.executeQuery();
            List<ChatEntry> result = new ArrayList<>();
            while (rs.next()) result.add(new ChatEntry(
                rs.getLong("id"), rs.getLong("ts"), rs.getString("player_uuid"),
                rs.getString("player_name"), rs.getString("message")));
            return result;
        }
    }

    public void pruneToMax(int maxRows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chat WHERE id NOT IN (SELECT id FROM chat ORDER BY ts DESC LIMIT ?)")) {
            ps.setInt(1, maxRows); ps.executeUpdate();
        }
    }
}
```

- [ ] **Step 6: HistoryRepository.java を作成**

```java
package dev.warasugi.wpanel.db;

import java.sql.*;
import java.util.*;

public class HistoryRepository {
    private final Connection conn;
    public record HistoryEntry(long id, long ts, String playerUuid, String playerName,
                               String eventType, String world, Double x, Double y, Double z) {}

    public HistoryRepository(Connection conn) { this.conn = conn; }

    public void insert(long ts, String uuid, String name, String eventType,
                       String world, double x, double y, double z) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_history(ts,player_uuid,player_name,event_type,world,x,y,z) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, ts); ps.setString(2, uuid); ps.setString(3, name);
            ps.setString(4, eventType); ps.setString(5, world);
            ps.setDouble(6, x); ps.setDouble(7, y); ps.setDouble(8, z);
            ps.executeUpdate();
        }
    }

    public List<HistoryEntry> queryByPlayer(String uuid, int pageSize, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM player_history WHERE player_uuid=? ORDER BY ts DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, uuid); ps.setInt(2, Math.min(pageSize, 500)); ps.setInt(3, offset);
            ResultSet rs = ps.executeQuery();
            List<HistoryEntry> result = new ArrayList<>();
            while (rs.next()) result.add(new HistoryEntry(
                rs.getLong("id"), rs.getLong("ts"), rs.getString("player_uuid"),
                rs.getString("player_name"), rs.getString("event_type"),
                rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")));
            return result;
        }
    }

    public void pruneToMax(int maxRows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM player_history WHERE id NOT IN (SELECT id FROM player_history ORDER BY ts DESC LIMIT ?)")) {
            ps.setInt(1, maxRows); ps.executeUpdate();
        }
    }
}
```

- [ ] **Step 7: AuditRepository.java を作成**

```java
package dev.warasugi.wpanel.db;

import java.sql.*;
import java.util.*;

public class AuditRepository {
    private final Connection conn;
    public record AuditEntry(long id, long ts, String sourceIp, String action, String detail) {}

    public AuditRepository(Connection conn) { this.conn = conn; }

    public void insert(long ts, String sourceIp, String action, String detail) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit(ts,source_ip,action,detail) VALUES(?,?,?,?)")) {
            ps.setLong(1, ts); ps.setString(2, sourceIp);
            ps.setString(3, action); ps.setString(4, detail);
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> query(int pageSize, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,ts,source_ip,action,detail FROM audit ORDER BY ts DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, Math.min(pageSize, 500)); ps.setInt(2, offset);
            ResultSet rs = ps.executeQuery();
            List<AuditEntry> result = new ArrayList<>();
            while (rs.next()) result.add(new AuditEntry(
                rs.getLong("id"), rs.getLong("ts"), rs.getString("source_ip"),
                rs.getString("action"), rs.getString("detail")));
            return result;
        }
    }
}
```

- [ ] **Step 8: テストが通ることを確認**

```powershell
.\gradlew.bat test --tests "dev.warasugi.wpanel.db.RepositoryTest"
```
Expected: `BUILD SUCCESSFUL`, 7 tests passed

- [ ] **Step 9: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/db/ \
        plugin/src/test/java/dev/warasugi/wpanel/db/
git commit -m "feat: SQLite永続化層 — DatabaseManager + 4 Repositories"
```

---

## Task 7: Auth HTTP ハンドラ

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/AuthHandler.java`

- [ ] **Step 1: AuthHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import dev.warasugi.wpanel.auth.*;
import dev.warasugi.wpanel.config.PanelConfig;
import io.javalin.http.*;
import java.util.UUID;

public class AuthHandler {
    private final TotpManager totp;
    private final JwtManager jwt;
    private final RateLimiter limiter;
    private final PanelConfig config;
    private volatile String oneTimeToken;
    private volatile long oneTimeExpiry;

    public AuthHandler(TotpManager totp, JwtManager jwt, RateLimiter limiter, PanelConfig config) {
        this.totp = totp; this.jwt = jwt; this.limiter = limiter; this.config = config;
    }

    /** POST /auth/login {"totp":"123456"} */
    public void login(Context ctx) {
        String ip = ctx.ip();
        if (!limiter.isAllowed(ip)) throw new HttpResponseException(429, "Too many attempts");

        record Body(String totp) {}
        String code = ctx.bodyAsClass(Body.class).totp();
        if (!this.totp.verify(code)) throw new HttpResponseException(401, "Invalid TOTP");

        limiter.reset(ip);
        String token = jwt.issue();
        String csrf  = UUID.randomUUID().toString();

        setJwtCookie(ctx, token);
        setCsrfCookie(ctx, csrf);
        ctx.json(new java.util.HashMap<>() {{ put("ok", true); }});
    }

    /** POST /auth/logout */
    public void logout(Context ctx) {
        ctx.removeCookie("jwt", "/");
        ctx.removeCookie("csrf_token", "/");
        ctx.status(204);
    }

    /** POST /auth/one-time {"token":"<32char>"} */
    public void oneTime(Context ctx) {
        record Body(String token) {}
        String provided = ctx.bodyAsClass(Body.class).token();
        if (oneTimeToken == null || System.currentTimeMillis() > oneTimeExpiry
                || !oneTimeToken.equals(provided)) {
            throw new HttpResponseException(401, "Invalid or expired token");
        }
        oneTimeToken = null;

        String token = jwt.issue();
        String csrf  = UUID.randomUUID().toString();
        setJwtCookie(ctx, token);
        setCsrfCookie(ctx, csrf);
        ctx.json(new java.util.HashMap<>() {{ put("ok", true); }});
    }

    /** /wpanel token コマンドから呼ばれる */
    public String generateOneTimeToken() {
        oneTimeToken  = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        oneTimeExpiry = System.currentTimeMillis() + 5 * 60_000L;
        return oneTimeToken;
    }

    private void setJwtCookie(Context ctx, String token) {
        Cookie c = new Cookie("jwt", token);
        c.setHttpOnly(true); c.setSecure(true);
        c.setSameSite(SameSite.STRICT); c.setPath("/");
        c.setMaxAge(config.getSessionHours() * 3600);
        ctx.cookie(c);
    }

    private void setCsrfCookie(Context ctx, String csrf) {
        Cookie c = new Cookie("csrf_token", csrf);
        c.setHttpOnly(false); c.setSecure(true);
        c.setSameSite(SameSite.STRICT); c.setPath("/");
        c.setMaxAge(config.getSessionHours() * 3600);
        ctx.cookie(c);
    }
}
```

- [ ] **Step 2: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/web/handlers/AuthHandler.java
git commit -m "feat: AuthHandler — TOTP認証・Cookie発行"
```

---

## Task 8: 認証ミドルウェア

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/middleware/AuthMiddleware.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/middleware/CsrfMiddleware.java`

- [ ] **Step 1: AuthMiddleware.java を作成**

```java
package dev.warasugi.wpanel.web.middleware;

import dev.warasugi.wpanel.auth.JwtManager;
import io.javalin.http.*;

public class AuthMiddleware {
    private final JwtManager jwt;

    public AuthMiddleware(JwtManager jwt) { this.jwt = jwt; }

    public void handle(Context ctx) {
        String path = ctx.path();
        // 認証不要パス
        if (path.equals("/auth/login") || path.equals("/auth/one-time")
                || path.startsWith("/assets/") || path.equals("/")
                || path.equals("/login")) return;

        String token = ctx.cookie("jwt");
        if (token == null || !jwt.isValid(token)) throw new UnauthorizedResponse();
    }
}
```

- [ ] **Step 2: CsrfMiddleware.java を作成**

```java
package dev.warasugi.wpanel.web.middleware;

import io.javalin.http.*;
import java.util.Set;

public class CsrfMiddleware {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public void handle(Context ctx) {
        if (SAFE_METHODS.contains(ctx.method().name())) return;
        if (ctx.path().equals("/auth/login") || ctx.path().equals("/auth/one-time")) return;

        String cookieCsrf  = ctx.cookie("csrf_token");
        String headerCsrf  = ctx.header("X-CSRF-Token");
        if (cookieCsrf == null || !cookieCsrf.equals(headerCsrf))
            throw new ForbiddenResponse("CSRF token mismatch");
    }
}
```

- [ ] **Step 3: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/web/middleware/
git commit -m "feat: AuthMiddleware + CsrfMiddleware"
```

---

## Task 9: REST ハンドラ群

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/StatusHandler.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/PlayerHandler.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/BanHandler.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/ChatHandler.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/ConsoleHandler.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/handlers/LogHandler.java`

- [ ] **Step 1: StatusHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import dev.warasugi.wpanel.metrics.MetricsCollector;
import io.javalin.http.Context;

public class StatusHandler {
    private final MetricsCollector metrics;

    public StatusHandler(MetricsCollector metrics) { this.metrics = metrics; }

    public void get(Context ctx) {
        ctx.json(metrics.getLatestSnapshot());
    }
}
```

- [ ] **Step 2: PlayerHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import io.javalin.http.Context;
import org.bukkit.Bukkit;
import java.util.*;

public class PlayerHandler {

    public void getPlayers(Context ctx) {
        // Bukkit APIはメインスレッドで呼ばれる必要があるため、
        // WebServer側でcallSyncMethodを使いこのメソッドを呼ぶこと
        List<Map<String, Object>> list = new ArrayList<>();
        for (var p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name",  p.getName());
            m.put("uuid",  p.getUniqueId().toString());
            m.put("ping",  p.getPing());
            m.put("world", p.getWorld().getName());
            m.put("x", p.getLocation().getX());
            m.put("y", p.getLocation().getY());
            m.put("z", p.getLocation().getZ());
            list.add(m);
        }
        ctx.json(list);
    }
}
```

- [ ] **Step 3: BanHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import dev.warasugi.wpanel.db.AuditRepository;
import io.javalin.http.*;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import java.time.*;
import java.util.*;

public class BanHandler {
    private final AuditRepository audit;

    public BanHandler(AuditRepository audit) { this.audit = audit; }

    public void getBans(Context ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("player", entry.getTarget());
            m.put("reason", entry.getReason());
            m.put("banner", entry.getSource());
            m.put("expires", entry.getExpiration() == null ? null : entry.getExpiration().getTime());
            list.add(m);
        }
        ctx.json(list);
    }

    public void addBan(Context ctx) {
        record Body(String player, String reason, Long duration) {}
        var body = ctx.bodyAsClass(Body.class);
        Date expires = body.duration() == null ? null
            : Date.from(Instant.now().plusSeconds(body.duration()));
        Bukkit.getBanList(BanList.Type.NAME).addBan(body.player(), body.reason(), expires, "WPanel");
        try { audit.insert(System.currentTimeMillis(), ctx.ip(), "ban",
            "{\"player\":\"" + body.player() + "\"}"); } catch (Exception ignored) {}
        ctx.status(201);
    }

    public void removeBan(Context ctx) {
        String player = ctx.pathParam("player");
        Bukkit.getBanList(BanList.Type.NAME).pardon(player);
        try { audit.insert(System.currentTimeMillis(), ctx.ip(), "unban",
            "{\"player\":\"" + player + "\"}"); } catch (Exception ignored) {}
        ctx.status(204);
    }

    public void getIpBans(Context ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var entry : Bukkit.getBanList(BanList.Type.IP).getBanEntries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", entry.getTarget());
            m.put("reason", entry.getReason());
            m.put("expires", entry.getExpiration() == null ? null : entry.getExpiration().getTime());
            list.add(m);
        }
        ctx.json(list);
    }

    public void addIpBan(Context ctx) {
        record Body(String ip, String reason) {}
        var body = ctx.bodyAsClass(Body.class);
        Bukkit.getBanList(BanList.Type.IP).addBan(body.ip(), body.reason(), null, "WPanel");
        ctx.status(201);
    }

    public void removeIpBan(Context ctx) {
        Bukkit.getBanList(BanList.Type.IP).pardon(ctx.pathParam("ip"));
        ctx.status(204);
    }
}
```

- [ ] **Step 4: ChatHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import dev.warasugi.wpanel.db.*;
import io.javalin.http.*;
import org.bukkit.Bukkit;

public class ChatHandler {
    private final ChatRepository chat;
    private final AuditRepository audit;

    public ChatHandler(ChatRepository chat, AuditRepository audit) {
        this.chat = chat; this.audit = audit;
    }

    public void getChat(Context ctx) throws Exception {
        String sinceStr = ctx.queryParam("since");
        Long since = sinceStr != null ? Long.parseLong(sinceStr) : null;
        int page = Integer.parseInt(ctx.queryParamAsClass("page", String.class).getOrDefault("0"));
        ctx.json(chat.query(since, 100, page * 100));
    }

    public void sendChat(Context ctx) throws Exception {
        record Body(String message) {}
        String msg = ctx.bodyAsClass(Body.class).message();
        // Bukkit APIはメインスレッド必須 → WebServer側でrunTask経由で呼ぶこと
        Bukkit.broadcastMessage("§6[Admin]§f " + msg);
        audit.insert(System.currentTimeMillis(), ctx.ip(), "chat_send",
            "{\"msg\":\"" + msg.replace("\"", "\\\"") + "\"}");
        ctx.status(204);
    }
}
```

- [ ] **Step 5: ConsoleHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import dev.warasugi.wpanel.config.PanelConfig;
import dev.warasugi.wpanel.db.AuditRepository;
import io.javalin.http.*;
import org.bukkit.Bukkit;

public class ConsoleHandler {
    private final PanelConfig config;
    private final AuditRepository audit;

    public ConsoleHandler(PanelConfig config, AuditRepository audit) {
        this.config = config; this.audit = audit;
    }

    public void execute(Context ctx) throws Exception {
        record Body(String command) {}
        String cmd = ctx.bodyAsClass(Body.class).command().strip();
        String root = cmd.split(" ")[0].toLowerCase();
        if (config.getCommandBlocklist().contains(root))
            throw new ForbiddenResponse("Command blocked: " + root);
        // Bukkit APIはメインスレッド必須 → WebServer側でrunTask経由で呼ぶこと
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        audit.insert(System.currentTimeMillis(), ctx.ip(), "console",
            "{\"cmd\":\"" + cmd.replace("\"", "\\\"") + "\"}");
        ctx.status(204);
    }
}
```

- [ ] **Step 6: LogHandler.java を作成**

```java
package dev.warasugi.wpanel.web.handlers;

import dev.warasugi.wpanel.db.LogRepository;
import io.javalin.http.Context;

public class LogHandler {
    private final LogRepository logs;

    public LogHandler(LogRepository logs) { this.logs = logs; }

    public void getLogs(Context ctx) throws Exception {
        String level = ctx.queryParam("level");
        String q = ctx.queryParam("q");
        int page = Integer.parseInt(ctx.queryParamAsClass("page", String.class).getOrDefault("0"));
        ctx.json(logs.query(level, q, 100, page * 100));
    }
}
```

- [ ] **Step 7: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/web/handlers/
git commit -m "feat: REST ハンドラ群 (Status/Player/Ban/Chat/Console/Log)"
```

---

## Task 10: MetricsCollector

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/metrics/MetricsCollector.java`

- [ ] **Step 1: MetricsCollector.java を作成**

```java
package dev.warasugi.wpanel.metrics;

import org.bukkit.Bukkit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MetricsCollector {
    private volatile Snapshot latest = new Snapshot(new double[]{20,20,20}, 0, 0, 0, 0);

    public record Snapshot(double[] tps, double mspt, int players, long uptime, long memoryUsed) {}

    private final long startTime = System.currentTimeMillis();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Bukkit Scheduler から2秒ごとに呼ばれる（メインスレッド） */
    public void tick() {
        double[] tps = Bukkit.getServer().getTPS();
        // MSPT: long[] nanoseconds → average in ms
        long[] times = Bukkit.getServer().getTickTimes();
        double mspt = Arrays.stream(times).average().orElse(0) / 1_000_000.0;
        int players = Bukkit.getOnlinePlayers().size();
        Runtime rt = Runtime.getRuntime();
        long memUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long uptime = (System.currentTimeMillis() - startTime) / 1000;

        latest = new Snapshot(tps, mspt, players, uptime, memUsed);
        listeners.forEach(Runnable::run);
    }

    public Snapshot getLatestSnapshot() { return latest; }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    /** ctx.json() で使うためにMapに変換 */
    public Map<String, Object> getLatestSnapshotAsMap() {
        Snapshot s = latest;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tps", s.tps());
        m.put("mspt", s.mspt());
        m.put("players", s.players());
        m.put("uptime", s.uptime());
        m.put("memoryUsedMb", s.memoryUsed());
        return m;
    }
}
```

- [ ] **Step 2: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/metrics/
git commit -m "feat: MetricsCollector — TPS/MSPT/メモリ収集"
```

---

## Task 11: AdminWsHandler + WebSocketAppender

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/ws/AdminWsHandler.java`
- Create: `plugin/src/main/java/dev/warasugi/wpanel/console/WebSocketAppender.java`

- [ ] **Step 1: AdminWsHandler.java を作成**

```java
package dev.warasugi.wpanel.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.warasugi.wpanel.auth.JwtManager;
import io.javalin.websocket.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AdminWsHandler {
    private final JwtManager jwt;
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminWsHandler(JwtManager jwt) { this.jwt = jwt; }

    public void onConnect(WsConnectContext ctx) {
        // Cookie は WebSocket ハンドシェイク時に送られる
        String token = ctx.cookie("jwt");
        if (token == null || !jwt.isValid(token)) { ctx.closeSession(4401, "Unauthorized"); return; }
        sessions.add(ctx);
    }

    public void onClose(WsCloseContext ctx) { sessions.remove(ctx); }

    public void onError(WsErrorContext ctx) { sessions.remove(ctx); }

    public void onMessage(WsMessageContext ctx) {
        // クライアントからのメッセージは WebServer 側で処理する
    }

    /** スレッドセーフなブロードキャスト */
    public void broadcast(String type, Object data) {
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "data", data));
            sessions.removeIf(s -> !s.session().isOpen());
            sessions.forEach(s -> s.send(json));
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: WebSocketAppender.java を作成**

```java
package dev.warasugi.wpanel.console;

import dev.warasugi.wpanel.db.LogRepository;
import dev.warasugi.wpanel.ws.AdminWsHandler;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import java.util.Map;

public class WebSocketAppender extends AbstractAppender {
    private final AdminWsHandler ws;
    private final LogRepository logRepo;

    public WebSocketAppender(AdminWsHandler ws, LogRepository logRepo) {
        super("WPanelWsAppender", null,
              PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
        this.ws = ws; this.logRepo = logRepo;
    }

    @Override
    public void append(LogEvent event) {
        String level   = event.getLevel().name();
        String logger  = event.getLoggerName();
        String message = event.getMessage().getFormattedMessage();
        long ts = event.getTimeMillis();

        // WS ブロードキャスト（スレッド非依存）
        ws.broadcast("log", Map.of("level", level, "msg", message, "time", ts));

        // SQLite 書き込み（try-with-resources で接続管理済みの Connection を使う）
        try { logRepo.insert(ts, level, logger, message); } catch (Exception ignored) {}
    }

    public static void register(AdminWsHandler ws, LogRepository logRepo) {
        var appender = new WebSocketAppender(ws, logRepo);
        appender.start();
        var ctx = (org.apache.logging.log4j.core.LoggerContext)
            org.apache.logging.log4j.LogManager.getContext(false);
        ctx.getConfiguration().addAppender(appender);
        ctx.getConfiguration().getRootLogger().addAppender(appender, null, null);
        ctx.updateLoggers();
    }
}
```

- [ ] **Step 3: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/ws/ \
        plugin/src/main/java/dev/warasugi/wpanel/console/
git commit -m "feat: AdminWsHandler + WebSocketAppender"
```

---

## Task 12: WebServer (Javalin 統合)

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/web/WebServer.java`

- [ ] **Step 1: WebServer.java を作成**

```java
package dev.warasugi.wpanel.web;

import dev.warasugi.wpanel.config.PanelConfig;
import dev.warasugi.wpanel.web.handlers.*;
import dev.warasugi.wpanel.web.middleware.*;
import dev.warasugi.wpanel.ws.AdminWsHandler;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.CompletableFuture;

public class WebServer {
    private final Javalin app;
    private final Plugin plugin;

    public WebServer(Plugin plugin, PanelConfig config,
                     AuthHandler auth, StatusHandler status, PlayerHandler player,
                     BanHandler ban, ChatHandler chat, ConsoleHandler console,
                     LogHandler log, AdminWsHandler ws,
                     AuthMiddleware authMw, CsrfMiddleware csrfMw) {
        this.plugin = plugin;

        app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;
            cfg.staticFiles.add("/web", Location.CLASSPATH);
            cfg.bundledPlugins.enableCors(cors ->
                cors.addRule(rule -> {
                    config.getCorsOrigins().forEach(rule::allowHost);
                    rule.allowCredentials = true;
                })
            );
        });

        // ミドルウェア
        app.before(authMw::handle);
        app.before(csrfMw::handle);

        // Auth
        app.post("/auth/login",    auth::login);
        app.post("/auth/logout",   auth::logout);
        app.post("/auth/one-time", auth::oneTime);

        // API — GET系はメインスレッド不要
        app.get("/api/status",  status::get);
        app.get("/api/logs",    log::getLogs);
        app.get("/api/chat",    chat::getChat);
        app.get("/api/history", ctx -> {
            String uuid = ctx.queryParam("player");
            int page = Integer.parseInt(ctx.queryParamAsClass("page", String.class).getOrDefault("0"));
            // HistoryHandlerに分離しても良いが、ここでは直接書く
            ctx.json(new Object()); // TODO: HistoryHandler に委譲
        });

        // Paper API 必須 → callSyncMethod でメインスレッドに委譲
        app.get("/api/players", ctx -> {
            var future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                player.getPlayers(ctx); return null;
            });
            future.get();
        });
        app.get("/api/bans",   ctx -> sync(() -> ban.getBans(ctx)));
        app.post("/api/bans",  ctx -> sync(() -> ban.addBan(ctx)));
        app.delete("/api/bans/{player}", ctx -> sync(() -> ban.removeBan(ctx)));
        app.get("/api/ipbans",          ctx -> sync(() -> ban.getIpBans(ctx)));
        app.post("/api/ipbans",         ctx -> sync(() -> ban.addIpBan(ctx)));
        app.delete("/api/ipbans/{ip}",  ctx -> sync(() -> ban.removeIpBan(ctx)));
        app.post("/api/chat",    ctx -> sync(() -> chat.sendChat(ctx)));
        app.post("/api/console", ctx -> sync(() -> console.execute(ctx)));

        // WebSocket
        app.ws("/ws", wsConfig -> {
            wsConfig.onConnect(ws::onConnect);
            wsConfig.onClose(ws::onClose);
            wsConfig.onError(ws::onError);
        });

        // SPA フォールバック（全未知パスを index.html に）
        app.error(404, ctx -> {
            if (ctx.header("Accept") != null && ctx.header("Accept").contains("text/html"))
                ctx.redirect("/");
        });
    }

    /** Paper APIをメインスレッドで実行してから Javalin に戻る */
    private void sync(RunnableEx r) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { r.run(); f.complete(null); }
            catch (Exception e) { f.completeExceptionally(e); }
        });
        f.get();
    }

    @FunctionalInterface
    interface RunnableEx { void run() throws Exception; }

    public void start(String host, int port) {
        app.start(host, port);
    }

    public void stop() { app.stop(); }
}
```

- [ ] **Step 2: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/web/WebServer.java
git commit -m "feat: WebServer — Javalin 6 ルーティング統合"
```

---

## Task 13: WPanelCommand

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/commands/WPanelCommand.java`

- [ ] **Step 1: WPanelCommand.java を作成**

```java
package dev.warasugi.wpanel.commands;

import dev.warasugi.wpanel.WPanelPlugin;
import dev.warasugi.wpanel.auth.TotpManager;
import dev.warasugi.wpanel.web.handlers.AuthHandler;
import org.bukkit.command.*;

public class WPanelCommand implements CommandExecutor {
    private final WPanelPlugin plugin;
    private final AuthHandler authHandler;

    public WPanelCommand(WPanelPlugin plugin, AuthHandler authHandler) {
        this.plugin = plugin; this.authHandler = authHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("使い方: /wpanel <setup|status|reload|token>"); return true; }

        switch (args[0].toLowerCase()) {
            case "setup" -> {
                if (!plugin.getConfig().getString("auth.totp-secret", "").isBlank()) {
                    sender.sendMessage("§cTOTPは設定済みです。再設定するにはconfig.ymlのtotp-secretを空にしてreloadしてください。");
                    return true;
                }
                String secret = TotpManager.generateSecret();
                plugin.getPanelConfig().setTotpSecret(secret);
                plugin.getConfig().set("auth.totp-secret", secret);
                plugin.saveConfig();
                plugin.reloadTotpManager();
                sender.sendMessage("§aTOTP設定完了。認証アプリで以下のURIをスキャンしてください:");
                sender.sendMessage(plugin.getTotpManager().getQrUri(plugin.getPanelConfig().getTotpIssuer()));
            }
            case "status" -> {
                var s = plugin.getMetricsCollector().getLatestSnapshotAsMap();
                sender.sendMessage("§aTPS: " + s.get("tps") + " | MSPT: " + s.get("mspt") + " | Players: " + s.get("players"));
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§aconfig.yml を再読込しました。");
            }
            case "token" -> {
                String token = authHandler.generateOneTimeToken();
                sender.sendMessage("§aワンタイムトークン (5分有効): §f" + token);
            }
            default -> sender.sendMessage("不明なサブコマンド: " + args[0]);
        }
        return true;
    }
}
```

- [ ] **Step 2: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/commands/
git commit -m "feat: WPanelCommand — setup/status/reload/token"
```

---

## Task 14: WPanelPlugin (全体統合)

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/wpanel/WPanelPlugin.java`

- [ ] **Step 1: WPanelPlugin.java を作成**

```java
package dev.warasugi.wpanel;

import dev.warasugi.wpanel.auth.*;
import dev.warasugi.wpanel.commands.WPanelCommand;
import dev.warasugi.wpanel.config.PanelConfig;
import dev.warasugi.wpanel.console.WebSocketAppender;
import dev.warasugi.wpanel.db.*;
import dev.warasugi.wpanel.metrics.MetricsCollector;
import dev.warasugi.wpanel.web.WebServer;
import dev.warasugi.wpanel.web.handlers.*;
import dev.warasugi.wpanel.web.middleware.*;
import dev.warasugi.wpanel.ws.AdminWsHandler;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;

public class WPanelPlugin extends JavaPlugin {
    private PanelConfig panelConfig;
    private TotpManager totpManager;
    private JwtManager jwtManager;
    private MetricsCollector metricsCollector;
    private DatabaseManager dbManager;
    private WebServer webServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        panelConfig = new PanelConfig(getConfig());

        Path dataDir = getDataFolder().toPath();
        getDataFolder().mkdirs();

        try {
            // DB
            dbManager = new DatabaseManager(dataDir.resolve("wpanel.db"));
            var logRepo  = new LogRepository(dbManager.getConnection());
            var chatRepo = new ChatRepository(dbManager.getConnection());
            var histRepo = new HistoryRepository(dbManager.getConnection());
            var auditRepo = new AuditRepository(dbManager.getConnection());

            // Auth
            String secret = panelConfig.getTotpSecret();
            totpManager = secret.isBlank() ? null : new TotpManager(secret);
            jwtManager = JwtManager.fromFile(dataDir.resolve("secret.key"),
                (long) panelConfig.getSessionHours() * 3600_000L);
            var rateLimiter = new RateLimiter(panelConfig.getLoginMaxAttempts(),
                panelConfig.getLoginLockoutMs());

            // Metrics
            metricsCollector = new MetricsCollector();

            // WS + Console Interceptor
            var wsHandler = new AdminWsHandler(jwtManager);
            WebSocketAppender.register(wsHandler, logRepo);

            // Metrics ブロードキャスト登録
            metricsCollector.addListener(() ->
                wsHandler.broadcast("metrics", metricsCollector.getLatestSnapshotAsMap()));

            // Handlers
            var authHandler    = new AuthHandler(
                totpManager != null ? totpManager : new TotpManager("DUMMY"),
                jwtManager, rateLimiter, panelConfig);
            var statusHandler  = new StatusHandler(metricsCollector);
            var playerHandler  = new PlayerHandler();
            var banHandler     = new BanHandler(auditRepo);
            var chatHandler    = new ChatHandler(chatRepo, auditRepo);
            var consoleHandler = new ConsoleHandler(panelConfig, auditRepo);
            var logHandler     = new LogHandler(logRepo);

            // Middleware
            var authMw = new AuthMiddleware(jwtManager);
            var csrfMw = new CsrfMiddleware();

            // Web Server
            webServer = new WebServer(this, panelConfig,
                authHandler, statusHandler, playerHandler,
                banHandler, chatHandler, consoleHandler,
                logHandler, wsHandler, authMw, csrfMw);
            webServer.start(panelConfig.getHost(), panelConfig.getPort());

            // Metrics ポーリング (2秒周期、メインスレッド)
            getServer().getScheduler().runTaskTimer(this, metricsCollector::tick, 0L, 40L);

            // コマンド登録
            getCommand("wpanel").setExecutor(new WPanelCommand(this, authHandler));

            getLogger().info("WPanel 起動完了 — port=" + panelConfig.getPort());
            if (totpManager == null)
                getLogger().warning("TOTP未設定。/wpanel setup を実行してください。");

        } catch (Exception e) {
            getLogger().severe("WPanel 起動失敗: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        try { if (dbManager != null) dbManager.close(); } catch (Exception ignored) {}
        getLogger().info("WPanel 停止完了");
    }

    public PanelConfig getPanelConfig() { return panelConfig; }
    public TotpManager getTotpManager() { return totpManager; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }

    public void reloadTotpManager() {
        totpManager = new TotpManager(panelConfig.getTotpSecret());
    }
}
```

- [ ] **Step 2: 全テスト実行**

```powershell
.\gradlew.bat test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Shadow JAR ビルド確認**

```powershell
.\gradlew.bat shadowJar
```
Expected: `BUILD SUCCESSFUL`, `plugin/build/libs/wpanel-1.0.0-all.jar` が生成される

- [ ] **Step 4: commit**

```bash
git add plugin/src/main/java/dev/warasugi/wpanel/WPanelPlugin.java
git commit -m "feat: WPanelPlugin — プラグイン全体統合"
```

---

## Task 15: フロントエンド scaffold

**Files:**
- Create: `frontend/` (Vite project)

- [ ] **Step 1: Vite プロジェクト生成**

```powershell
cd (repo root)
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
npm install apexcharts react-apexcharts xterm @xterm/addon-fit
npm install -D openapi-typescript
```

- [ ] **Step 2: tailwind.config.ts を設定**

```ts
// frontend/tailwind.config.ts
import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: { primary: '#0a0e27', secondary: '#111828', card: '#141c35' },
        accent: { primary: '#10b981', glow: '#34d399' },
      },
    },
  },
  plugins: [],
} satisfies Config
```

- [ ] **Step 3: src/index.css を作成**

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

body { background-color: #0a0e27; color: #e5e7eb; }
```

- [ ] **Step 4: 開発サーバー起動確認**

```powershell
npm run dev
```
Expected: ブラウザで `http://localhost:5173` が開き React のデフォルト画面が表示される

- [ ] **Step 5: commit**

```bash
git add frontend/
git commit -m "feat: フロントエンド Vite+React+TS+Tailwind scaffold"
```

---

## Task 16: API クライアント (fetch ラッパ + OpenAPI 型)

**Files:**
- Create: `frontend/src/lib/api.ts`
- Create: `frontend/src/lib/ws.ts`
- Create: `frontend/openapi-ts.config.ts`

- [ ] **Step 1: OpenAPI 型生成設定を作成**

```ts
// frontend/openapi-ts.config.ts
import { defineConfig } from 'openapi-typescript'

export default defineConfig({
  input: '../plugin/build/openapi/openapi.json',  // Javalin OpenAPI plugin が生成するパス
  output: './src/lib/api-types.ts',
})
```

※ Javalin OpenAPI plugin のビルド出力パスは実際のプラグイン設定に合わせて調整すること。

- [ ] **Step 2: api.ts を作成**

```ts
// frontend/src/lib/api.ts
// api-types.ts は openapi-typescript で自動生成。手書き禁止。

function getCsrfToken(): string {
  return document.cookie
    .split('; ')
    .find(r => r.startsWith('csrf_token='))
    ?.split('=')[1] ?? ''
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    headers['X-CSRF-Token'] = getCsrfToken()
  }

  const res = await fetch(path, { ...options, credentials: 'include', headers })
  if (res.status === 401) { window.location.href = '/login'; throw new Error('Unauthorized') }
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const api = {
  login:   (totp: string) => request<void>('/auth/login', { method: 'POST', body: JSON.stringify({ totp }) }),
  logout:  () => request<void>('/auth/logout', { method: 'POST' }),
  status:  () => request<unknown>('/api/status'),
  players: () => request<unknown[]>('/api/players'),
  bans:    () => request<unknown[]>('/api/bans'),
  addBan:  (player: string, reason: string, duration?: number) =>
    request<void>('/api/bans', { method: 'POST', body: JSON.stringify({ player, reason, duration }) }),
  removeBan: (player: string) => request<void>(`/api/bans/${player}`, { method: 'DELETE' }),
  ipbans:  () => request<unknown[]>('/api/ipbans'),
  addIpBan: (ip: string, reason: string) =>
    request<void>('/api/ipbans', { method: 'POST', body: JSON.stringify({ ip, reason }) }),
  removeIpBan: (ip: string) => request<void>(`/api/ipbans/${ip}`, { method: 'DELETE' }),
  logs:    (page = 0, level?: string, q?: string) =>
    request<unknown[]>(`/api/logs?page=${page}${level ? `&level=${level}` : ''}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
  chat:    (page = 0) => request<unknown[]>(`/api/chat?page=${page}`),
  sendChat: (message: string) =>
    request<void>('/api/chat', { method: 'POST', body: JSON.stringify({ message }) }),
  sendCommand: (command: string) =>
    request<void>('/api/console', { method: 'POST', body: JSON.stringify({ command }) }),
  history: (player: string, page = 0) => request<unknown[]>(`/api/history?player=${player}&page=${page}`),
}
```

- [ ] **Step 3: ws.ts を作成**

```ts
// frontend/src/lib/ws.ts
type MessageHandler = (type: string, data: unknown) => void

export class WsClient {
  private ws: WebSocket | null = null
  private handlers: MessageHandler[] = []
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    this.ws = new WebSocket(`${proto}://${location.host}/ws`)

    this.ws.onmessage = (e) => {
      try {
        const { type, data } = JSON.parse(e.data)
        this.handlers.forEach(h => h(type, data))
      } catch { /* ignore malformed */ }
    }

    this.ws.onclose = () => {
      this.reconnectTimer = setTimeout(() => this.connect(), 3000)
    }
  }

  onMessage(handler: MessageHandler) { this.handlers.push(handler) }

  send(type: string, payload: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN)
      this.ws.send(JSON.stringify({ type, ...payload as object }))
  }

  disconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.ws?.close()
  }
}

export const wsClient = new WsClient()
```

- [ ] **Step 4: commit**

```bash
git add frontend/src/lib/ frontend/openapi-ts.config.ts
git commit -m "feat: APIクライアント + WebSocketクライアント"
```

---

## Task 17: Login ページ

**Files:**
- Create: `frontend/src/pages/Login.tsx`

- [ ] **Step 1: Login.tsx を作成**

```tsx
// frontend/src/pages/Login.tsx
import { useState } from 'react'
import { api } from '../lib/api'

export default function Login() {
  const [code, setCode]     = useState('')
  const [error, setError]   = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await api.login(code)
      window.location.href = '/'
    } catch {
      setError('認証コードが正しくありません')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg-primary">
      <div className="bg-bg-card p-8 rounded-2xl shadow-2xl w-full max-w-sm border border-white/5">
        <h1 className="text-2xl font-bold text-accent-primary mb-6 text-center">WPanel</h1>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm text-gray-400 mb-1">認証コード (6桁)</label>
            <input
              type="text" inputMode="numeric" pattern="[0-9]{6}"
              maxLength={6} value={code}
              onChange={e => setCode(e.target.value)}
              className="w-full bg-bg-secondary border border-white/10 rounded-lg px-4 py-2
                         text-white text-center text-2xl tracking-widest focus:outline-none
                         focus:border-accent-primary"
              placeholder="000000"
              autoFocus
            />
          </div>
          {error && <p className="text-red-400 text-sm text-center">{error}</p>}
          <button type="submit" disabled={loading || code.length !== 6}
            className="w-full bg-accent-primary hover:bg-accent-glow disabled:opacity-40
                       text-white font-semibold py-2 rounded-lg transition-colors">
            {loading ? 'ログイン中...' : 'ログイン'}
          </button>
        </form>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: App.tsx にルーティングを設定**

```powershell
cd frontend
npm install react-router-dom
```

```tsx
// frontend/src/App.tsx
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/*" element={<Dashboard />} />
      </Routes>
    </BrowserRouter>
  )
}
```

- [ ] **Step 3: 動作確認** — `npm run dev` 後 `/login` にアクセスしてフォームが表示されることを確認

- [ ] **Step 4: commit**

```bash
git add frontend/src/pages/Login.tsx frontend/src/App.tsx frontend/package.json frontend/package-lock.json
git commit -m "feat: Loginページ + react-router-dom"
```

---

## Task 18: Dashboard + リアルタイムグラフ

**Files:**
- Create: `frontend/src/components/layout/Sidebar.tsx`
- Create: `frontend/src/components/charts/TpsChart.tsx`
- Create: `frontend/src/components/charts/MsptChart.tsx`
- Create: `frontend/src/pages/Dashboard.tsx`

- [ ] **Step 1: Sidebar.tsx を作成**

```tsx
// frontend/src/components/layout/Sidebar.tsx
import { NavLink } from 'react-router-dom'

const links = [
  { to: '/',         label: 'Dashboard' },
  { to: '/terminal', label: 'Terminal' },
  { to: '/players',  label: 'Players' },
  { to: '/chat',     label: 'Chat' },
  { to: '/bans',     label: 'Bans' },
  { to: '/logs',     label: 'Logs' },
  { to: '/history',  label: 'History' },
]

export default function Sidebar() {
  return (
    <aside className="w-48 bg-bg-secondary h-screen flex flex-col py-6 px-4 border-r border-white/5">
      <div className="text-accent-primary font-bold text-xl mb-8">WPanel</div>
      <nav className="space-y-1">
        {links.map(l => (
          <NavLink key={l.to} to={l.to} end={l.to === '/'}
            className={({ isActive }) =>
              `block px-3 py-2 rounded-lg text-sm transition-colors ${
                isActive ? 'bg-accent-primary/20 text-accent-primary' : 'text-gray-400 hover:text-white'
              }`}>
            {l.label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
```

- [ ] **Step 2: TpsChart.tsx を作成**

```tsx
// frontend/src/components/charts/TpsChart.tsx
import ReactApexChart from 'react-apexcharts'
import { ApexOptions } from 'apexcharts'

interface Props { series: { name: string; data: number[] }[] }

export default function TpsChart({ series }: Props) {
  const options: ApexOptions = {
    chart: { type: 'line', background: 'transparent', toolbar: { show: false },
             animations: { enabled: true, speed: 300 } },
    colors: ['#10b981', '#34d399', '#6ee7b7'],
    stroke: { curve: 'smooth', width: 2 },
    xaxis: { labels: { show: false } },
    yaxis: { min: 0, max: 20, labels: { style: { colors: '#9ca3af' } } },
    grid: { borderColor: '#1f2937' },
    legend: { labels: { colors: '#9ca3af' } },
    theme: { mode: 'dark' },
    annotations: {
      yaxis: [
        { y: 15, borderColor: '#f59e0b', label: { text: '警告', style: { color: '#f59e0b' } } },
        { y: 10, borderColor: '#ef4444', label: { text: '緊急', style: { color: '#ef4444' } } },
      ],
    },
  }
  return <ReactApexChart options={options} series={series} type="line" height={200} />
}
```

- [ ] **Step 3: Dashboard.tsx を作成**

```tsx
// frontend/src/pages/Dashboard.tsx
import { useEffect, useState } from 'react'
import { Routes, Route } from 'react-router-dom'
import Sidebar from '../components/layout/Sidebar'
import TpsChart from '../components/charts/TpsChart'
import { wsClient } from '../lib/ws'

interface Metrics {
  tps: [number, number, number]
  mspt: number
  players: number
  uptime: number
  memoryUsedMb: number
}

function DashboardHome() {
  const [metrics, setMetrics] = useState<Metrics | null>(null)
  const [tpsHistory, setTpsHistory] = useState<number[]>([])

  useEffect(() => {
    wsClient.connect()
    wsClient.onMessage((type, data) => {
      if (type !== 'metrics') return
      const m = data as Metrics
      setMetrics(m)
      setTpsHistory(prev => [...prev.slice(-59), m.tps[0]])
    })
    return () => wsClient.disconnect()
  }, [])

  if (!metrics) return <div className="p-8 text-gray-400">接続中...</div>

  return (
    <div className="p-6 space-y-6">
      <div className="grid grid-cols-4 gap-4">
        {[
          { label: 'TPS (1min)', value: metrics.tps[0].toFixed(1) },
          { label: 'MSPT',       value: metrics.mspt.toFixed(1) + 'ms' },
          { label: 'Players',    value: metrics.players },
          { label: 'Memory',     value: metrics.memoryUsedMb + 'MB' },
        ].map(card => (
          <div key={card.label} className="bg-bg-card rounded-xl p-4 border border-white/5">
            <div className="text-gray-400 text-sm">{card.label}</div>
            <div className="text-2xl font-bold text-accent-primary mt-1">{card.value}</div>
          </div>
        ))}
      </div>
      <div className="bg-bg-card rounded-xl p-4 border border-white/5">
        <div className="text-sm text-gray-400 mb-2">TPS (1min)</div>
        <TpsChart series={[{ name: 'TPS', data: tpsHistory }]} />
      </div>
    </div>
  )
}

export default function Dashboard() {
  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <main className="flex-1 overflow-auto bg-bg-primary">
        <Routes>
          <Route index element={<DashboardHome />} />
          <Route path="terminal" element={<div className="p-8">Terminal (Task 19)</div>} />
          <Route path="players"  element={<div className="p-8">Players (Task 20)</div>} />
          <Route path="chat"     element={<div className="p-8">Chat (Task 20)</div>} />
          <Route path="bans"     element={<div className="p-8">Bans (Task 20)</div>} />
          <Route path="logs"     element={<div className="p-8">Logs (Task 20)</div>} />
          <Route path="history"  element={<div className="p-8">History (Task 20)</div>} />
        </Routes>
      </main>
    </div>
  )
}
```

- [ ] **Step 4: commit**

```bash
git add frontend/src/components/ frontend/src/pages/Dashboard.tsx
git commit -m "feat: Dashboard — リアルタイムメトリクス + TPS グラフ"
```

---

## Task 19: Terminal ページ

**Files:**
- Create: `frontend/src/pages/Terminal.tsx`

- [ ] **Step 1: Terminal.tsx を作成**

```tsx
// frontend/src/pages/Terminal.tsx
import { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import { wsClient } from '../lib/ws'
import { api } from '../lib/api'
import 'xterm/css/xterm.css'

export default function Terminal() {
  const termRef = useRef<HTMLDivElement>(null)
  const xtermRef = useRef<XTerm | null>(null)
  const [cmd, setCmd] = useState('')

  useEffect(() => {
    const term = new XTerm({
      theme: { background: '#0a0e27', foreground: '#e5e7eb', cursor: '#10b981' },
      fontSize: 13, fontFamily: 'monospace',
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(termRef.current!)
    fit.fit()
    xtermRef.current = term

    wsClient.onMessage((type, data) => {
      if (type === 'log' || type === 'console') {
        const d = data as { line?: string; msg?: string; level?: string }
        term.writeln(d.line ?? d.msg ?? '')
      }
    })

    const ro = new ResizeObserver(() => fit.fit())
    ro.observe(termRef.current!)
    return () => { ro.disconnect(); term.dispose() }
  }, [])

  async function sendCommand(e: React.FormEvent) {
    e.preventDefault()
    if (!cmd.trim()) return
    try { await api.sendCommand(cmd) } catch { /* handle */ }
    setCmd('')
  }

  return (
    <div className="flex flex-col h-full p-4 space-y-2">
      <div ref={termRef} className="flex-1 rounded-lg overflow-hidden" />
      <form onSubmit={sendCommand} className="flex gap-2">
        <input value={cmd} onChange={e => setCmd(e.target.value)}
          className="flex-1 bg-bg-secondary border border-white/10 rounded-lg px-4 py-2 text-white text-sm focus:outline-none focus:border-accent-primary"
          placeholder="コマンドを入力..." />
        <button type="submit"
          className="bg-accent-primary hover:bg-accent-glow px-4 py-2 rounded-lg text-white text-sm font-semibold">
          実行
        </button>
      </form>
    </div>
  )
}
```

- [ ] **Step 2: commit**

```bash
git add frontend/src/pages/Terminal.tsx
git commit -m "feat: Terminal ページ — xterm.js + コマンド送信"
```

---

## Task 20: 残りのページ (Players / Chat / Bans / Logs / History)

**Files:**
- Create: `frontend/src/pages/Players.tsx`
- Create: `frontend/src/pages/Chat.tsx`
- Create: `frontend/src/pages/Bans.tsx`
- Create: `frontend/src/pages/Logs.tsx`
- Create: `frontend/src/pages/History.tsx`

- [ ] **Step 1: Players.tsx を作成**

```tsx
// frontend/src/pages/Players.tsx
import { useEffect, useState } from 'react'
import { api } from '../lib/api'

interface Player { name: string; uuid: string; ping: number; world: string; x: number; y: number; z: number }

export default function Players() {
  const [players, setPlayers] = useState<Player[]>([])

  useEffect(() => {
    api.players().then(d => setPlayers(d as Player[]))
    const id = setInterval(() => api.players().then(d => setPlayers(d as Player[])), 5000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="p-6">
      <h2 className="text-lg font-bold text-white mb-4">オンラインプレイヤー ({players.length})</h2>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-gray-400 border-b border-white/10">
            <th className="text-left py-2">名前</th><th className="text-left py-2">Ping</th>
            <th className="text-left py-2">ワールド</th><th className="text-left py-2">座標</th>
          </tr>
        </thead>
        <tbody>
          {players.map(p => (
            <tr key={p.uuid} className="border-b border-white/5 hover:bg-white/5">
              <td className="py-2 text-accent-primary">{p.name}</td>
              <td className="py-2">{p.ping}ms</td>
              <td className="py-2">{p.world}</td>
              <td className="py-2">{p.x.toFixed(0)}, {p.y.toFixed(0)}, {p.z.toFixed(0)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 2: Bans.tsx を作成**

```tsx
// frontend/src/pages/Bans.tsx
import { useEffect, useState } from 'react'
import { api } from '../lib/api'

interface Ban { player: string; reason: string; expires: number | null; banner: string }

export default function Bans() {
  const [bans, setBans] = useState<Ban[]>([])
  const [player, setPlayer] = useState(''); const [reason, setReason] = useState('')

  const reload = () => api.bans().then(d => setBans(d as Ban[]))
  useEffect(() => { reload() }, [])

  async function addBan(e: React.FormEvent) {
    e.preventDefault()
    await api.addBan(player, reason)
    setPlayer(''); setReason('')
    reload()
  }

  return (
    <div className="p-6 space-y-6">
      <form onSubmit={addBan} className="flex gap-2">
        <input value={player} onChange={e => setPlayer(e.target.value)} placeholder="プレイヤー名"
          className="bg-bg-secondary border border-white/10 rounded-lg px-3 py-2 text-white text-sm flex-1 focus:outline-none" />
        <input value={reason} onChange={e => setReason(e.target.value)} placeholder="理由"
          className="bg-bg-secondary border border-white/10 rounded-lg px-3 py-2 text-white text-sm flex-1 focus:outline-none" />
        <button type="submit" className="bg-red-600 hover:bg-red-500 text-white px-4 py-2 rounded-lg text-sm">BAN</button>
      </form>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-gray-400 border-b border-white/10">
            <th className="text-left py-2">Player</th><th className="text-left py-2">理由</th>
            <th className="text-left py-2">期限</th><th className="text-left py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          {bans.map(b => (
            <tr key={b.player} className="border-b border-white/5">
              <td className="py-2 text-accent-primary">{b.player}</td>
              <td className="py-2">{b.reason}</td>
              <td className="py-2">{b.expires ? new Date(b.expires).toLocaleDateString('ja-JP') : '永続'}</td>
              <td className="py-2">
                <button onClick={() => api.removeBan(b.player).then(reload)}
                  className="text-red-400 hover:text-red-300 text-xs">解除</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 3: Logs.tsx を作成**

```tsx
// frontend/src/pages/Logs.tsx
import { useEffect, useState } from 'react'
import { api } from '../lib/api'

interface Log { id: number; ts: number; level: string; logger: string; message: string }
const LEVELS = ['', 'INFO', 'WARN', 'ERROR']
const levelColor: Record<string, string> = { INFO: 'text-blue-400', WARN: 'text-yellow-400', ERROR: 'text-red-400' }

export default function Logs() {
  const [logs, setLogs] = useState<Log[]>([])
  const [level, setLevel] = useState(''); const [q, setQ] = useState(''); const [page, setPage] = useState(0)

  useEffect(() => { api.logs(page, level || undefined, q || undefined).then(d => setLogs(d as Log[])) }, [page, level, q])

  return (
    <div className="p-6 space-y-4">
      <div className="flex gap-2">
        <select value={level} onChange={e => { setLevel(e.target.value); setPage(0) }}
          className="bg-bg-secondary border border-white/10 rounded-lg px-3 py-2 text-white text-sm">
          {LEVELS.map(l => <option key={l} value={l}>{l || '全レベル'}</option>)}
        </select>
        <input value={q} onChange={e => { setQ(e.target.value); setPage(0) }} placeholder="キーワード検索..."
          className="flex-1 bg-bg-secondary border border-white/10 rounded-lg px-3 py-2 text-white text-sm focus:outline-none" />
      </div>
      <div className="font-mono text-xs space-y-0.5">
        {logs.map(l => (
          <div key={l.id} className="flex gap-2 py-0.5">
            <span className="text-gray-500 w-24 shrink-0">{new Date(l.ts).toLocaleTimeString('ja-JP')}</span>
            <span className={`w-12 shrink-0 ${levelColor[l.level] ?? 'text-gray-400'}`}>{l.level}</span>
            <span className="text-gray-300">{l.message}</span>
          </div>
        ))}
      </div>
      <div className="flex gap-2 justify-center">
        <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
          className="text-sm text-gray-400 hover:text-white disabled:opacity-30">← 前</button>
        <span className="text-sm text-gray-400">{page + 1}</span>
        <button onClick={() => setPage(p => p + 1)} disabled={logs.length < 100}
          className="text-sm text-gray-400 hover:text-white disabled:opacity-30">次 →</button>
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Chat.tsx を作成**

```tsx
// frontend/src/pages/Chat.tsx
import { useEffect, useRef, useState } from 'react'
import { api } from '../lib/api'
import { wsClient } from '../lib/ws'

interface ChatMsg { id: number; ts: number; playerName: string; message: string }

export default function Chat() {
  const [messages, setMessages] = useState<ChatMsg[]>([])
  const [msg, setMsg] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.chat().then(d => setMessages((d as ChatMsg[]).reverse()))
    wsClient.onMessage((type, data) => {
      if (type !== 'chat') return
      const d = data as { player: string; msg: string; time: number }
      setMessages(prev => [...prev, { id: Date.now(), ts: d.time, playerName: d.player, message: d.msg }])
    })
  }, [])

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])

  async function send(e: React.FormEvent) {
    e.preventDefault()
    if (!msg.trim()) return
    await api.sendChat(msg)
    setMsg('')
  }

  return (
    <div className="flex flex-col h-full p-4">
      <div className="flex-1 overflow-auto space-y-2 mb-4">
        {messages.map(m => (
          <div key={m.id} className="text-sm">
            <span className="text-gray-500 text-xs">{new Date(m.ts).toLocaleTimeString('ja-JP')} </span>
            <span className="text-accent-primary font-semibold">{m.playerName}: </span>
            <span className="text-gray-300">{m.message}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={send} className="flex gap-2">
        <input value={msg} onChange={e => setMsg(e.target.value)}
          className="flex-1 bg-bg-secondary border border-white/10 rounded-lg px-3 py-2 text-white text-sm focus:outline-none"
          placeholder="[Admin] メッセージを送信..." />
        <button type="submit" className="bg-accent-primary hover:bg-accent-glow text-white px-4 py-2 rounded-lg text-sm">送信</button>
      </form>
    </div>
  )
}
```

- [ ] **Step 5: History.tsx を作成**

```tsx
// frontend/src/pages/History.tsx
import { useState } from 'react'
import { api } from '../lib/api'

interface HistoryEntry { id: number; ts: number; playerName: string; eventType: string; world: string; x: number; y: number; z: number }

export default function History() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<HistoryEntry[]>([])

  async function search(e: React.FormEvent) {
    e.preventDefault()
    if (!query.trim()) return
    const data = await api.history(query)
    setResults(data as HistoryEntry[])
  }

  return (
    <div className="p-6 space-y-4">
      <form onSubmit={search} className="flex gap-2">
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder="UUID または名前で検索..."
          className="flex-1 bg-bg-secondary border border-white/10 rounded-lg px-3 py-2 text-white text-sm focus:outline-none" />
        <button type="submit" className="bg-accent-primary text-white px-4 py-2 rounded-lg text-sm">検索</button>
      </form>
      <div className="space-y-1 text-sm">
        {results.map(r => (
          <div key={r.id} className="flex gap-3 py-1 border-b border-white/5">
            <span className="text-gray-500 w-32 shrink-0">{new Date(r.ts).toLocaleString('ja-JP')}</span>
            <span className="text-accent-primary w-16 shrink-0">{r.eventType}</span>
            <span className="text-gray-300">{r.playerName}</span>
            {r.world && <span className="text-gray-500">{r.world} ({r.x?.toFixed(0)}, {r.y?.toFixed(0)}, {r.z?.toFixed(0)})</span>}
          </div>
        ))}
      </div>
    </div>
  )
}
```

- [ ] **Step 6: Dashboard.tsx の Route を実際のコンポーネントに差し替え**

```tsx
// frontend/src/pages/Dashboard.tsx の Routes 部分を更新
import Terminal from './Terminal'
import Players from './Players'
import Chat from './Chat'
import Bans from './Bans'
import Logs from './Logs'
import History from './History'

// <Routes> の中を以下に変更:
// <Route index element={<DashboardHome />} />
// <Route path="terminal" element={<Terminal />} />
// <Route path="players"  element={<Players />} />
// <Route path="chat"     element={<Chat />} />
// <Route path="bans"     element={<Bans />} />
// <Route path="logs"     element={<Logs />} />
// <Route path="history"  element={<History />} />
```

- [ ] **Step 7: フロントエンドビルド確認**

```powershell
cd frontend
npm run build
```
Expected: `dist/` が生成される

- [ ] **Step 8: commit**

```bash
git add frontend/src/pages/
git commit -m "feat: 残りの全ページ (Players/Chat/Bans/Logs/History)"
```

---

## Task 21: フロントエンド → JAR バンドル統合

**Files:**
- Modify: `plugin/build.gradle.kts`

- [ ] **Step 1: build.gradle.kts にフロント統合タスクを追加**

```kotlin
// plugin/build.gradle.kts の末尾に追加
val buildFrontend = tasks.register<Exec>("buildFrontend") {
    workingDir = file("../frontend")
    commandLine("npm", "run", "build")
    inputs.dir("../frontend/src")
    outputs.dir("../frontend/dist")
}

tasks.processResources {
    dependsOn(buildFrontend)
    from("../frontend/dist") { into("web") }
}
```

- [ ] **Step 2: フロント込みで JAR をビルド**

```powershell
cd plugin
.\gradlew.bat shadowJar
```
Expected: `BUILD SUCCESSFUL`。JAR 内に `web/` ディレクトリが含まれることを確認:

```powershell
jar tf build/libs/wpanel-1.0.0-all.jar | Select-String "web/"
```

- [ ] **Step 3: .gitignore を作成**

```gitignore
# plugin/
plugin/build/
plugin/.gradle/

# frontend/
frontend/node_modules/
frontend/dist/
frontend/src/lib/api-types.ts
```

- [ ] **Step 4: commit**

```bash
git add plugin/build.gradle.kts .gitignore
git commit -m "chore: フロントエンドをshadowJarに自動バンドル"
```

---

## 検証手順

1. **単体テスト:** `cd plugin && .\gradlew.bat test` → BUILD SUCCESSFUL
2. **フロントビルド:** `cd frontend && npm run build` → dist/ 生成
3. **JAR ビルド:** `cd plugin && .\gradlew.bat shadowJar` → `build/libs/wpanel-1.0.0-all.jar`
4. **実機テスト:**
   - Paper 1.21.x サーバーに JAR を配置して起動
   - `/wpanel setup` でQR表示 → 認証アプリでスキャン
   - `https://ホスト名/login` にアクセス → TOTP 入力 → Dashboard 表示
   - TPS/MSPT がリアルタイム更新されることを確認
   - Terminal でコマンド実行 → ログに反映されることを確認
   - BAN 追加・解除が `/ban` コマンドと同期していることを確認
