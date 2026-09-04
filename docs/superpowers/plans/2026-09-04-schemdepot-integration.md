# WARP × SchemDepot 連携 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WARP のダッシュボードに SchemDepot のアセット一覧・総容量・作者別の件数と容量を表示する。SchemDepot が未導入なら項目ごと非表示になる。

**Architecture:** WARP は SchemDepot に対してコンパイル依存も `plugin.yml` の `depend`/`softdepend` も持たない。`plugins/SchemDepot/` を read-only SQLite 接続とファイル `stat` だけで読む疎結合構成。読み取り結果は 30 秒 TTL のスナップショットにまとめ、3 本の `GET` API から返す。

**Tech Stack:** Java 21, Paper API 1.21.1, Javalin 6.4.0, xerial/sqlite-jdbc 3.46.1.3（すべて既存依存。**新規依存の追加はない**）, React 19 + TypeScript + Vite + Tailwind

**Spec:** `docs/superpowers/specs/2026-09-04-schemdepot-integration-design.md`

---

## Global Constraints

設計書 §2「既存アセットを絶対に失わない」はユーザーからの明示指示であり、全タスクの暗黙の要件。以下は例外なく守ること。

1. **書き込み系 SQL をコードに存在させない。** 発行してよいのは `SELECT` と読み取り専用 `PRAGMA`（`user_version` / `query_only`）のみ。`INSERT` / `UPDATE` / `DELETE` / `CREATE` / `DROP` / `ALTER` / `VACUUM` という語をプロダクションコードに書かない。
2. **接続は read-only で開く。** `SQLiteConfig.setReadOnly(true)` + 接続直後に `PRAGMA query_only = ON`。
3. **読み書きモードへフォールバックしない。** 失敗時は `OPEN_FAILED` として degrade する。
4. **`.schem` はサイズと存在の読み取りのみ。** `Files.delete` / `Files.move` / `Files.write` / `Files.newOutputStream` / `Files.copy` を書かない。
5. **不整合は報告のみ。** 孤児ファイルを削除しない。
6. **`/api/schemdepot/*` は `GET` のみ。** `post` / `delete` / `put` / `patch` を登録しない。
7. **`build.gradle.kts` と `plugin.yml` を変更しない。**
8. **レスポンスにファイルシステムの絶対パスを含めない。** 返すのはファイル名のみ。
9. **Bukkit API を呼ばない。** このパッケージは純粋な JDBC + NIO のみで完結させる（`callSyncMethod` 不要）。
10. **その他の既存ファイルを触らない。** 変更してよい既存ファイルは各タスクの `Modify` に挙げたものだけ。

**作業ブランチ:** `feat/schemdepot-integration`

**共通の実行コマンド:**
- プラグインのテスト: `cd plugin && ./gradlew test --tests '<パターン>'`
- フロントエンドの型チェック: `cd frontend && npx tsc -b --noEmit`

---

## File Structure

```
plugin/src/main/java/dev/warasugi/warp/schemdepot/
├── SchemDepotPaths.java        新規  データフォルダ内の解決済みパス3点
├── SchemDepotUnavailable.java  新規  利用不可の理由を表す enum
├── SchemDepotLocator.java      新規  フォルダ検出と config.yml のパス解決・検証
├── SchemDepotAsset.java        新規  アセット1件のDTO
├── AuthorStat.java             新規  作者別集計のDTO
├── Integrity.java              新規  不整合(欠損/孤児)のDTO
├── SchemDepotSnapshot.java     新規  1回の読み取り結果すべて
└── SchemDepotReader.java       新規  read-only読み取り + 集計 + TTLキャッシュ

plugin/src/main/java/dev/warasugi/warp/web/handlers/
└── SchemDepotHandler.java      新規  GET 3本

plugin/src/main/java/dev/warasugi/warp/
└── WarpPlugin.java             変更  initWeb に2行追加

plugin/src/test/java/dev/warasugi/warp/schemdepot/
├── SchemDepotTestFixture.java  新規  テスト用のSchemDepot相当ツリー生成
├── SchemDepotLocatorTest.java  新規
├── SchemDepotReaderTest.java   新規
└── SchemDepotReadOnlyTest.java 新規  絶対制約の回帰テスト

plugin/src/test/java/dev/warasugi/warp/web/handlers/
└── SchemDepotHandlerTest.java  新規

frontend/src/
├── lib/format.ts               新規  formatBytes
├── lib/api.ts                  変更  3メソッド追加
├── hooks/useSchemDepot.ts      新規  status を1回だけ取得して共有
├── pages/SchemDepot.tsx        新規
├── App.tsx                     変更  ルート追加 + Provider
├── components/layout/Sidebar.tsx  変更  条件表示
└── pages/Dashboard.tsx         変更  カード1枚追加
```

責務の分割方針: `Locator`（どこを読むか）と `Reader`（どう読むか）を分けることで、SchemDepot 実体なしに Locator を単体テストできる。DTO を個別ファイルに切ることで、`Reader` と `Handler` の両方から同じ型を参照でき、ハンドラ内 `record` の重複定義を避ける。

---

## Task 1: パス検出（Locator）

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/SchemDepotPaths.java`
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/SchemDepotUnavailable.java`
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/SchemDepotLocator.java`
- Test: `plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotLocatorTest.java`

**Interfaces:**
- Consumes: なし（最初のタスク）
- Produces:
  - `record SchemDepotPaths(Path root, Path database, Path schematics)`
  - `enum SchemDepotUnavailable { NOT_INSTALLED, NO_DATABASE, NOT_MIGRATED, SCHEMA_TOO_NEW, OPEN_FAILED, READ_FAILED }` — `String code()` を持つ
  - `SchemDepotLocator.Result` = `record Result(SchemDepotPaths paths, SchemDepotUnavailable reason)` with `boolean available()`
  - `static Result SchemDepotLocator.locate(Path pluginsDir, Logger logger)`
  - `static String SchemDepotLocator.sanitizeName(String value, String fallback, String key, Logger logger)`（package-private, テスト用）

- [ ] **Step 1: 失敗するテストを書く**

`plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotLocatorTest.java`:

```java
package dev.warasugi.warp.schemdepot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SchemDepotLocatorTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void notInstalled_whenFolderMissing(@TempDir Path plugins) {
        var result = SchemDepotLocator.locate(plugins, LOG);
        assertFalse(result.available());
        assertEquals(SchemDepotUnavailable.NOT_INSTALLED, result.reason());
    }

    @Test
    void noDatabase_whenFolderExistsButDbMissing(@TempDir Path plugins) throws IOException {
        Files.createDirectories(plugins.resolve("SchemDepot"));
        var result = SchemDepotLocator.locate(plugins, LOG);
        assertFalse(result.available());
        assertEquals(SchemDepotUnavailable.NO_DATABASE, result.reason());
    }

    @Test
    void usesDefaults_whenConfigMissing(@TempDir Path plugins) throws IOException {
        Path root = plugins.resolve("SchemDepot");
        Files.createDirectories(root);
        Files.createFile(root.resolve("assets.db"));

        var result = SchemDepotLocator.locate(plugins, LOG);
        assertTrue(result.available());
        assertEquals(root.resolve("assets.db"), result.paths().database());
        assertEquals(root.resolve("schematics"), result.paths().schematics());
    }

    @Test
    void honoursConfiguredNames(@TempDir Path plugins) throws IOException {
        Path root = plugins.resolve("SchemDepot");
        Files.createDirectories(root);
        Files.createFile(root.resolve("registry.db"));
        Files.writeString(root.resolve("config.yml"),
                "storage:\n  database-file: \"registry.db\"\n  schematics-directory: \"schems\"\n");

        var result = SchemDepotLocator.locate(plugins, LOG);
        assertTrue(result.available());
        assertEquals(root.resolve("registry.db"), result.paths().database());
        assertEquals(root.resolve("schems"), result.paths().schematics());
    }

    @Test
    void rejectsTraversalAndFallsBackToDefault() {
        assertEquals("assets.db",
                SchemDepotLocator.sanitizeName("../../etc/passwd", "assets.db", "k", LOG));
        assertEquals("assets.db",
                SchemDepotLocator.sanitizeName("/etc/passwd", "assets.db", "k", LOG));
        assertEquals("assets.db",
                SchemDepotLocator.sanitizeName("..\\..\\world", "assets.db", "k", LOG));
        assertEquals("assets.db",
                SchemDepotLocator.sanitizeName("..", "assets.db", "k", LOG));
        assertEquals("assets.db",
                SchemDepotLocator.sanitizeName("  ", "assets.db", "k", LOG));
        assertEquals("assets.db",
                SchemDepotLocator.sanitizeName(null, "assets.db", "k", LOG));
        assertEquals("registry.db",
                SchemDepotLocator.sanitizeName("registry.db", "assets.db", "k", LOG));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.schemdepot.SchemDepotLocatorTest'`
Expected: コンパイルエラー（`SchemDepotLocator` などが存在しない）

- [ ] **Step 3: `SchemDepotPaths` を実装**

```java
package dev.warasugi.warp.schemdepot;

import java.nio.file.Path;

/** SchemDepot のデータフォルダ内で解決済みのパス。すべて読み取り専用に扱う。 */
public record SchemDepotPaths(Path root, Path database, Path schematics) {}
```

- [ ] **Step 4: `SchemDepotUnavailable` を実装**

```java
package dev.warasugi.warp.schemdepot;

/** SchemDepot 連携が利用できない理由。code() はそのまま HTTP レスポンスの reason になる。 */
public enum SchemDepotUnavailable {
    NOT_INSTALLED("not_installed"),
    NO_DATABASE("no_database"),
    NOT_MIGRATED("not_migrated"),
    SCHEMA_TOO_NEW("schema_too_new"),
    OPEN_FAILED("open_failed"),
    READ_FAILED("read_failed");

    private final String code;

    SchemDepotUnavailable(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

- [ ] **Step 5: `SchemDepotLocator` を実装**

`config.yml` は `YamlConfiguration.loadFromString` で読む（`loadConfiguration(File)` は Bukkit の静的ロガーに触れる可能性があるため、テスト可能性を優先してファイル読み込みを自前で行う）。

```java
package dev.warasugi.warp.schemdepot;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * plugins/SchemDepot/ を検出し、config.yml から DB とスキマティック置き場のパスを解決する。
 *
 * ここで解決したパスは読み取りにしか使わない。SchemDepot 本体の
 * SchemDepotConfig.requireNonBlankPath と同じ「単一パス要素」制約を課し、
 * データフォルダの外を読みに行かないようにする。
 */
public final class SchemDepotLocator {

    public static final String FOLDER_NAME = "SchemDepot";
    private static final String DEFAULT_DATABASE = "assets.db";
    private static final String DEFAULT_SCHEMATICS = "schematics";
    private static final char[] SEPARATORS = {'/', '\\', ':'};

    private SchemDepotLocator() {}

    public record Result(SchemDepotPaths paths, SchemDepotUnavailable reason) {
        public boolean available() {
            return paths != null;
        }

        static Result ok(SchemDepotPaths paths) {
            return new Result(paths, null);
        }

        static Result unavailable(SchemDepotUnavailable reason) {
            return new Result(null, reason);
        }
    }

    public static Result locate(Path pluginsDir, Logger logger) {
        Path root = pluginsDir.resolve(FOLDER_NAME);
        if (!Files.isDirectory(root)) {
            return Result.unavailable(SchemDepotUnavailable.NOT_INSTALLED);
        }

        String databaseName = DEFAULT_DATABASE;
        String schematicsName = DEFAULT_SCHEMATICS;

        Path configFile = root.resolve("config.yml");
        if (Files.isRegularFile(configFile)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(configFile));
                databaseName = sanitizeName(
                        yaml.getString("storage.database-file"),
                        DEFAULT_DATABASE, "storage.database-file", logger);
                schematicsName = sanitizeName(
                        yaml.getString("storage.schematics-directory"),
                        DEFAULT_SCHEMATICS, "storage.schematics-directory", logger);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "SchemDepot の config.yml を読めませんでした。既定のパスを使います。", e);
            }
        }

        Path database = root.resolve(databaseName);
        if (!Files.isRegularFile(database)) {
            return Result.unavailable(SchemDepotUnavailable.NO_DATABASE);
        }
        return Result.ok(new SchemDepotPaths(root, database, root.resolve(schematicsName)));
    }

    /** 単一のファイル名/ディレクトリ名でなければ fallback に落とす。 */
    static String sanitizeName(String value, String fallback, String key, Logger logger) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String reason = rejectionReason(value);
        if (reason != null) {
            logger.warning("SchemDepot の config.yml: " + key + " が \"" + value
                    + "\" (" + reason + ")。\"" + fallback + "\" を使います。");
            return fallback;
        }
        return value;
    }

    private static String rejectionReason(String value) {
        if (value.equals(".") || value.equals("..")) {
            return "相対パス要素";
        }
        for (char separator : SEPARATORS) {
            if (value.indexOf(separator) >= 0) {
                return "パス区切りまたはドライブレターを含む";
            }
        }
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            return "このプラットフォームで無効なファイル名";
        }
        if (path.isAbsolute() || path.getRoot() != null) {
            return "絶対パス";
        }
        if (path.getNameCount() != 1) {
            return "複数のパス要素を含む";
        }
        return null;
    }
}
```

- [ ] **Step 6: テストが通ることを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.schemdepot.SchemDepotLocatorTest'`
Expected: PASS（6 テスト）

- [ ] **Step 7: コミット**

```bash
git add plugin/src/main/java/dev/warasugi/warp/schemdepot plugin/src/test/java/dev/warasugi/warp/schemdepot
git commit -m "feat: SchemDepotのデータフォルダ検出とパス解決を追加"
```

---

## Task 2: DTO とテスト用フィクスチャ

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/SchemDepotAsset.java`
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/AuthorStat.java`
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/Integrity.java`
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/SchemDepotSnapshot.java`
- Test: `plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotTestFixture.java`

**Interfaces:**
- Consumes: Task 1 の `SchemDepotPaths`
- Produces:
  - `record SchemDepotAsset(String id, String name, String authorUuid, String authorName, long createdAt, long updatedAt, int sizeX, int sizeY, int sizeZ, long volume, long bytes, boolean fileMissing)`
  - `record AuthorStat(String uuid, String name, int count, long bytes, double share)`
  - `record Integrity(List<MissingFile> missingFiles, List<OrphanFile> orphanFiles, long orphanBytes, boolean schematicsUnreadable)` — 入れ子に `record MissingFile(String id, String name, String file)` と `record OrphanFile(String file, long bytes)`、`static Integrity empty()`
  - `record SchemDepotSnapshot(List<SchemDepotAsset> assets, int totalCount, long totalBytes, int authorCount, List<AuthorStat> authors, Integrity integrity)`
  - `public final class SchemDepotTestFixture`（Task 3・4・5 の全テストが共有する。`web.handlers` パッケージからも使うため public）
  - `static SchemDepotTestFixture create(Path pluginsDir)`、`addAsset(String id, String name, String authorUuid, String authorName, long createdAt, int sizeX, int sizeY, int sizeZ, int bytes)`、`addOrphanFile(String name, int bytes)`、`setUserVersion(int)`、`root()` / `database()` / `schematics()`

- [ ] **Step 1: DTO を4つ実装**

`SchemDepotAsset.java`:

```java
package dev.warasugi.warp.schemdepot;

/**
 * アセット1件。createdAt/updatedAt は epoch millis
 * (SchemDepot 側が Instant.toEpochMilli() で保存している)。
 * bytes は .schem の実測サイズ。ファイルが無い場合は 0 かつ fileMissing = true。
 */
public record SchemDepotAsset(
        String id,
        String name,
        String authorUuid,
        String authorName,
        long createdAt,
        long updatedAt,
        int sizeX,
        int sizeY,
        int sizeZ,
        long volume,
        long bytes,
        boolean fileMissing) {}
```

`AuthorStat.java`:

```java
package dev.warasugi.warp.schemdepot;

/** 作者別の集計。share は totalBytes に対する占有率 (0.0〜1.0)。 */
public record AuthorStat(String uuid, String name, int count, long bytes, double share) {}
```

`Integrity.java`:

```java
package dev.warasugi.warp.schemdepot;

import java.util.List;

/**
 * レジストリとファイル実体の不整合。検出して報告するだけで、削除は一切行わない
 * (設計書 §2-5)。
 */
public record Integrity(
        List<MissingFile> missingFiles,
        List<OrphanFile> orphanFiles,
        long orphanBytes,
        boolean schematicsUnreadable) {

    /** DB に行はあるが .schem が存在しないもの。 */
    public record MissingFile(String id, String name, String file) {}

    /** schematics/ にあるがどの行からも参照されていない .schem。 */
    public record OrphanFile(String file, long bytes) {}

    public static Integrity empty() {
        return new Integrity(List.of(), List.of(), 0L, false);
    }
}
```

`SchemDepotSnapshot.java`:

```java
package dev.warasugi.warp.schemdepot;

import java.util.List;

/** 1回の読み取り結果すべて。3本の API はこれ1つから応答する。 */
public record SchemDepotSnapshot(
        List<SchemDepotAsset> assets,
        int totalCount,
        long totalBytes,
        int authorCount,
        List<AuthorStat> authors,
        Integrity integrity) {}
```

- [ ] **Step 2: テスト用フィクスチャを実装**

SchemDepot 相当のツリーを一時ディレクトリに作る。**テストコード内でのみ**書き込み系 SQL を使う（プロダクションコードには存在させない — Global Constraints 1 はプロダクションコードに対する制約）。

`plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotTestFixture.java`:

```java
package dev.warasugi.warp.schemdepot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * テスト用に plugins/SchemDepot/ 相当のツリーを作る。
 *
 * 書き込み系 SQL をここに閉じ込めることで、プロダクションコード側の禁止
 * (Global Constraints 1) を保ちつつテストデータを用意できる。
 * dev.warasugi.warp.web.handlers のテストからも使うため public。
 */
public final class SchemDepotTestFixture {

    private final Path root;
    private final Path database;
    private final Path schematics;

    private SchemDepotTestFixture(Path root, Path database, Path schematics) {
        this.root = root;
        this.database = database;
        this.schematics = schematics;
    }

    public static SchemDepotTestFixture create(Path pluginsDir) throws IOException, SQLException {
        Path root = pluginsDir.resolve("SchemDepot");
        Path schematics = root.resolve("schematics");
        Files.createDirectories(schematics);
        Path database = root.resolve("assets.db");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE assets (
                        id              TEXT PRIMARY KEY,
                        name            TEXT NOT NULL,
                        normalized_name TEXT NOT NULL UNIQUE,
                        author_uuid     TEXT NOT NULL,
                        author_name     TEXT NOT NULL,
                        created_at      INTEGER NOT NULL,
                        updated_at      INTEGER NOT NULL,
                        size_x          INTEGER NOT NULL,
                        size_y          INTEGER NOT NULL,
                        size_z          INTEGER NOT NULL,
                        schematic_file  TEXT NOT NULL UNIQUE
                    )""");
            st.execute("PRAGMA user_version = 1");
        }
        return new SchemDepotTestFixture(root, database, schematics);
    }

    /** 行と、対応する .schem を bytes バイトで作る。bytes < 0 ならファイルを作らない(欠損)。 */
    public SchemDepotTestFixture addAsset(String id, String name, String authorUuid, String authorName,
                                          long createdAt, int sizeX, int sizeY, int sizeZ, int bytes)
            throws IOException, SQLException {
        String file = id + ".schem";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO assets (id, name, normalized_name, author_uuid, author_name,"
                             + " created_at, updated_at, size_x, size_y, size_z, schematic_file)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase());
            ps.setString(4, authorUuid);
            ps.setString(5, authorName);
            ps.setLong(6, createdAt);
            ps.setLong(7, createdAt);
            ps.setInt(8, sizeX);
            ps.setInt(9, sizeY);
            ps.setInt(10, sizeZ);
            ps.setString(11, file);
            ps.executeUpdate();
        }
        if (bytes >= 0) {
            Files.write(schematics.resolve(file), new byte[bytes]);
        }
        return this;
    }

    public SchemDepotTestFixture addOrphanFile(String fileName, int bytes) throws IOException {
        Files.write(schematics.resolve(fileName), new byte[bytes]);
        return this;
    }

    public SchemDepotTestFixture setUserVersion(int version) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version = " + version);
        }
        return this;
    }

    public Path root() {
        return root;
    }

    public Path database() {
        return database;
    }

    public Path schematics() {
        return schematics;
    }
}
```

- [ ] **Step 3: コンパイルを確認**

Run: `cd plugin && ./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add plugin/src/main/java/dev/warasugi/warp/schemdepot plugin/src/test/java/dev/warasugi/warp/schemdepot
git commit -m "feat: SchemDepot連携のDTOとテスト用フィクスチャを追加"
```

---

## Task 3: Reader — read-only 読み取り・集計・キャッシュ

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/warp/schemdepot/SchemDepotReader.java`
- Test: `plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotReaderTest.java`

**Interfaces:**
- Consumes: Task 1 の `SchemDepotLocator` / `SchemDepotPaths` / `SchemDepotUnavailable`、Task 2 の DTO 群と `SchemDepotTestFixture`
- Produces:
  - `record SchemDepotReader.Result(SchemDepotSnapshot snapshot, SchemDepotUnavailable reason)` with `boolean available()`
  - `SchemDepotReader(Path pluginsDir, Logger logger)` — 本番用、TTL 30 秒
  - `SchemDepotReader(Path pluginsDir, Logger logger, long ttlMillis, LongSupplier clock)` — テスト用
  - `Result read()` — キャッシュ経由でスナップショットを返す

- [ ] **Step 1: 失敗するテストを書く**

`plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotReaderTest.java`:

```java
package dev.warasugi.warp.schemdepot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SchemDepotReaderTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_B = "22222222-2222-2222-2222-222222222222";

    private SchemDepotReader reader(Path plugins) {
        return new SchemDepotReader(plugins, LOG);
    }

    @Test
    void unavailable_whenNotInstalled(@TempDir Path plugins) {
        var result = reader(plugins).read();
        assertFalse(result.available());
        assertEquals(SchemDepotUnavailable.NOT_INSTALLED, result.reason());
    }

    @Test
    void readsAssetsWithMeasuredFileSizes(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "House", UUID_A, "alice", 1000L, 2, 3, 4, 100)
                .addAsset("a-2", "Tower", UUID_B, "bob", 2000L, 1, 1, 1, 50);

        var result = reader(plugins).read();
        assertTrue(result.available());
        var snap = result.snapshot();

        assertEquals(2, snap.totalCount());
        assertEquals(150L, snap.totalBytes());
        assertEquals(2, snap.authorCount());

        var house = snap.assets().stream().filter(a -> a.id().equals("a-1")).findFirst().orElseThrow();
        assertEquals("House", house.name());
        assertEquals("alice", house.authorName());
        assertEquals(1000L, house.createdAt());
        assertEquals(24L, house.volume());
        assertEquals(100L, house.bytes());
        assertFalse(house.fileMissing());
    }

    @Test
    void aggregatesByAuthorSortedByBytesDescending(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "Small", UUID_A, "alice", 1000L, 1, 1, 1, 10)
                .addAsset("a-2", "Big", UUID_B, "bob", 2000L, 1, 1, 1, 90)
                .addAsset("a-3", "Also", UUID_B, "bob", 3000L, 1, 1, 1, 0);

        var snap = reader(plugins).read().snapshot();
        assertEquals(2, snap.authors().size());

        AuthorStat first = snap.authors().get(0);
        assertEquals("bob", first.name());
        assertEquals(2, first.count());
        assertEquals(90L, first.bytes());
        assertEquals(0.9d, first.share(), 1e-9);

        AuthorStat second = snap.authors().get(1);
        assertEquals("alice", second.name());
        assertEquals(1, second.count());
        assertEquals(0.1d, second.share(), 1e-9);
    }

    @Test
    void detectsMissingAndOrphanFiles(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "Present", UUID_A, "alice", 1000L, 1, 1, 1, 30)
                .addAsset("a-2", "Gone", UUID_A, "alice", 2000L, 1, 1, 1, -1)
                .addOrphanFile("stray.schem", 70);

        var snap = reader(plugins).read().snapshot();

        assertEquals(1, snap.integrity().missingFiles().size());
        assertEquals("a-2", snap.integrity().missingFiles().get(0).id());

        assertEquals(1, snap.integrity().orphanFiles().size());
        assertEquals("stray.schem", snap.integrity().orphanFiles().get(0).file());
        assertEquals(70L, snap.integrity().orphanBytes());

        // 欠損分は容量に加算されず、孤児も totalBytes には含めない
        assertEquals(30L, snap.totalBytes());

        var gone = snap.assets().stream().filter(a -> a.id().equals("a-2")).findFirst().orElseThrow();
        assertTrue(gone.fileMissing());
        assertEquals(0L, gone.bytes());
    }

    @Test
    void degradesWhenSchemaTooNew(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "House", UUID_A, "alice", 1000L, 1, 1, 1, 10)
                .setUserVersion(2);

        var result = reader(plugins).read();
        assertFalse(result.available());
        assertEquals(SchemDepotUnavailable.SCHEMA_TOO_NEW, result.reason());
    }

    @Test
    void degradesWhenNotMigrated(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins).setUserVersion(0);

        var result = reader(plugins).read();
        assertFalse(result.available());
        assertEquals(SchemDepotUnavailable.NOT_MIGRATED, result.reason());
    }

    @Test
    void reportsUnreadableSchematicsDirectory(@TempDir Path plugins) throws Exception {
        var fixture = SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "House", UUID_A, "alice", 1000L, 1, 1, 1, -1);
        // schematics をディレクトリではなくファイルにして「読めない」状態を作る
        Files.delete(fixture.schematics());
        Files.writeString(fixture.schematics(), "not a directory");

        var snap = reader(plugins).read().snapshot();
        assertTrue(snap.integrity().schematicsUnreadable());
        assertEquals(1, snap.totalCount(), "容量が取れなくても一覧自体は返す");
        assertEquals(0L, snap.totalBytes());
    }

    @Test
    void doesNotCacheOpenFailure(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "House", UUID_A, "alice", 1000L, 1, 1, 1, 10);

        Path database = plugins.resolve("SchemDepot").resolve("assets.db");
        byte[] valid = Files.readAllBytes(database);
        Files.write(database, "this is not a sqlite database".getBytes());

        var reader = reader(plugins);
        assertEquals(SchemDepotUnavailable.OPEN_FAILED, reader.read().reason());

        // 失敗をキャッシュしていれば TTL 内の再読み取りも失敗したままになる
        Files.write(database, valid);
        assertTrue(reader.read().available(), "一過性の失敗はキャッシュしない");
    }

    @Test
    void cachesWithinTtlAndRefreshesAfter(@TempDir Path plugins) throws Exception {
        var fixture = SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "One", UUID_A, "alice", 1000L, 1, 1, 1, 10);

        AtomicLong now = new AtomicLong(0L);
        var reader = new SchemDepotReader(plugins, LOG, 30_000L, now::get);

        assertEquals(1, reader.read().snapshot().totalCount());

        fixture.addAsset("a-2", "Two", UUID_A, "alice", 2000L, 1, 1, 1, 10);

        now.set(29_999L);
        assertEquals(1, reader.read().snapshot().totalCount(), "TTL 内はキャッシュを返す");

        now.set(30_001L);
        assertEquals(2, reader.read().snapshot().totalCount(), "TTL 経過後は読み直す");
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.schemdepot.SchemDepotReaderTest'`
Expected: コンパイルエラー（`SchemDepotReader` が存在しない）

- [ ] **Step 3: `SchemDepotReader` を実装**

```java
package dev.warasugi.warp.schemdepot;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SchemDepot のレジストリを読み取り専用で読む。
 *
 * 設計書 §2 の絶対制約: このクラスは SELECT と読み取り専用 PRAGMA しか発行せず、
 * 接続は SQLITE_OPEN_READONLY + PRAGMA query_only の二重防御で開く。読み取り専用で
 * 開けなかった場合に読み書きへ昇格することは決してしない (WAL チェックポイントによる
 * 本体ファイルへの書き込みを避けるため)。.schem に対しても Files.size() 以外は行わない。
 */
public final class SchemDepotReader {

    /** WARP が解釈できる SchemDepot のスキーマ版 (PRAGMA user_version)。 */
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final long DEFAULT_TTL_MILLIS = 30_000L;
    private static final String SCHEMATIC_SUFFIX = ".schem";

    private final Path pluginsDir;
    private final Logger logger;
    private final long ttlMillis;
    private final LongSupplier clock;

    private final ReentrantLock lock = new ReentrantLock();
    private Result cached;
    private long cachedAt;
    private SchemDepotUnavailable lastLoggedReason;

    public SchemDepotReader(Path pluginsDir, Logger logger) {
        this(pluginsDir, logger, DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    SchemDepotReader(Path pluginsDir, Logger logger, long ttlMillis, LongSupplier clock) {
        this.pluginsDir = pluginsDir;
        this.logger = logger;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    public record Result(SchemDepotSnapshot snapshot, SchemDepotUnavailable reason) {
        public boolean available() {
            return snapshot != null;
        }

        static Result ok(SchemDepotSnapshot snapshot) {
            return new Result(snapshot, null);
        }

        static Result unavailable(SchemDepotUnavailable reason) {
            return new Result(null, reason);
        }
    }

    public Result read() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            if (cached != null && now - cachedAt < ttlMillis) {
                return cached;
            }
            Result fresh = load();
            if (isTransientFailure(fresh)) {
                // 設計書 §8: 読み取り失敗時はキャッシュを更新せず、前回値も破棄する
                cached = null;
                return fresh;
            }
            cached = fresh;
            cachedAt = now;
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    private static boolean isTransientFailure(Result result) {
        return result.reason() == SchemDepotUnavailable.OPEN_FAILED
                || result.reason() == SchemDepotUnavailable.READ_FAILED;
    }

    private Result load() {
        SchemDepotLocator.Result located = SchemDepotLocator.locate(pluginsDir, logger);
        if (!located.available()) {
            return unavailable(located.reason());
        }
        SchemDepotPaths paths = located.paths();

        List<Row> rows;
        try (Connection conn = openReadOnly(paths.database())) {
            int version = readUserVersion(conn);
            if (version < SUPPORTED_SCHEMA_VERSION) {
                return unavailable(SchemDepotUnavailable.NOT_MIGRATED);
            }
            if (version > SUPPORTED_SCHEMA_VERSION) {
                return unavailable(SchemDepotUnavailable.SCHEMA_TOO_NEW);
            }
            rows = selectAll(conn);
        } catch (SQLException e) {
            logOnce(SchemDepotUnavailable.OPEN_FAILED,
                    "SchemDepot のレジストリを読み取り専用で開けませんでした。", e);
            return new Result(null, SchemDepotUnavailable.OPEN_FAILED);
        } catch (RuntimeException e) {
            logOnce(SchemDepotUnavailable.READ_FAILED,
                    "SchemDepot のレジストリの読み取りに失敗しました。", e);
            return new Result(null, SchemDepotUnavailable.READ_FAILED);
        }

        lastLoggedReason = null;
        return Result.ok(buildSnapshot(rows, paths.schematics()));
    }

    private Result unavailable(SchemDepotUnavailable reason) {
        if (reason == SchemDepotUnavailable.SCHEMA_TOO_NEW
                || reason == SchemDepotUnavailable.NOT_MIGRATED) {
            logOnce(reason, "SchemDepot のスキーマ版が WARP の対応範囲外です ("
                    + reason.code() + ")。連携表示を無効にします。", null);
        }
        return Result.unavailable(reason);
    }

    /**
     * 読み取り専用接続を開く。setReadOnly(true) が SQLITE_OPEN_READONLY を、
     * PRAGMA query_only が SQLite エンジン側の文レベル拒否を担当する二重の安全弁。
     */
    private static Connection openReadOnly(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA query_only = ON");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    private static int readUserVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private record Row(String id, String name, String authorUuid, String authorName,
                       long createdAt, long updatedAt, int sizeX, int sizeY, int sizeZ,
                       String schematicFile) {}

    private static List<Row> selectAll(Connection conn) throws SQLException {
        String sql = "SELECT id, name, author_uuid, author_name, created_at, updated_at,"
                + " size_x, size_y, size_z, schematic_file FROM assets";
        List<Row> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Row(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("author_uuid"),
                        rs.getString("author_name"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        rs.getInt("size_x"),
                        rs.getInt("size_y"),
                        rs.getInt("size_z"),
                        rs.getString("schematic_file")));
            }
        }
        return rows;
    }

    private SchemDepotSnapshot buildSnapshot(List<Row> rows, Path schematicsDir) {
        SchematicFiles files = listSchematicSizes(schematicsDir);
        Map<String, Long> fileSizes = files.sizes();

        List<SchemDepotAsset> assets = new ArrayList<>(rows.size());
        List<Integrity.MissingFile> missing = new ArrayList<>();
        Set<String> referenced = new HashSet<>();
        long totalBytes = 0L;

        for (Row row : rows) {
            referenced.add(row.schematicFile());
            Long size = fileSizes.get(row.schematicFile());
            boolean fileMissing = size == null;
            long bytes = fileMissing ? 0L : size;
            totalBytes += bytes;
            if (fileMissing) {
                missing.add(new Integrity.MissingFile(row.id(), row.name(), row.schematicFile()));
            }
            assets.add(new SchemDepotAsset(
                    row.id(), row.name(), row.authorUuid(), row.authorName(),
                    row.createdAt(), row.updatedAt(),
                    row.sizeX(), row.sizeY(), row.sizeZ(),
                    (long) row.sizeX() * row.sizeY() * row.sizeZ(),
                    bytes, fileMissing));
        }

        List<Integrity.OrphanFile> orphans = new ArrayList<>();
        long orphanBytes = 0L;
        for (Map.Entry<String, Long> entry : fileSizes.entrySet()) {
            if (!referenced.contains(entry.getKey())) {
                orphans.add(new Integrity.OrphanFile(entry.getKey(), entry.getValue()));
                orphanBytes += entry.getValue();
            }
        }
        orphans.sort(Comparator.comparing(Integrity.OrphanFile::file));

        List<AuthorStat> authors = aggregateAuthors(assets, totalBytes);

        return new SchemDepotSnapshot(
                List.copyOf(assets), assets.size(), totalBytes, authors.size(),
                authors, new Integrity(List.copyOf(missing), List.copyOf(orphans), orphanBytes,
                        files.unreadable()));
    }

    private static List<AuthorStat> aggregateAuthors(List<SchemDepotAsset> assets, long totalBytes) {
        record Acc(String name, int count, long bytes) {}
        Map<String, Acc> byUuid = new LinkedHashMap<>();
        for (SchemDepotAsset asset : assets) {
            byUuid.merge(asset.authorUuid(),
                    new Acc(asset.authorName(), 1, asset.bytes()),
                    // 表示名は最後に観測したものを採用する (改名時は新しい方が出る)
                    (oldAcc, newAcc) -> new Acc(newAcc.name(),
                            oldAcc.count() + newAcc.count(),
                            oldAcc.bytes() + newAcc.bytes()));
        }
        List<AuthorStat> stats = new ArrayList<>(byUuid.size());
        for (Map.Entry<String, Acc> entry : byUuid.entrySet()) {
            Acc acc = entry.getValue();
            double share = totalBytes == 0L ? 0d : (double) acc.bytes() / totalBytes;
            stats.add(new AuthorStat(entry.getKey(), acc.name(), acc.count(), acc.bytes(), share));
        }
        stats.sort(Comparator.comparingLong(AuthorStat::bytes).reversed()
                .thenComparing(AuthorStat::name));
        return List.copyOf(stats);
    }

    /** ファイル名 → バイト数と、ディレクトリ自体が読めたかどうか。 */
    private record SchematicFiles(Map<String, Long> sizes, boolean unreadable) {}

    /**
     * schematics/ 直下の .schem のファイル名 → バイト数。
     * Files.size() しか呼ばない (設計書 §2-4)。読めない場合も一覧自体は返し、
     * unreadable = true として integrity に残す (設計書 §8)。
     * ディレクトリが単に存在しない場合は「まだ何も無い」として unreadable にしない。
     */
    private SchematicFiles listSchematicSizes(Path schematicsDir) {
        Map<String, Long> sizes = new HashMap<>();
        if (!Files.exists(schematicsDir)) {
            return new SchematicFiles(sizes, false);
        }
        if (!Files.isDirectory(schematicsDir)) {
            logger.warning("SchemDepot の schematics がディレクトリではありません。容量を集計できません。");
            return new SchematicFiles(sizes, true);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(schematicsDir, "*" + SCHEMATIC_SUFFIX)) {
            for (Path file : stream) {
                try {
                    sizes.put(file.getFileName().toString(), Files.size(file));
                } catch (IOException e) {
                    // 個別の失敗は欠損として扱い、全体は成功させる
                    logger.log(Level.FINE, "SchemDepot のスキマティックのサイズを取得できませんでした。", e);
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "SchemDepot の schematics ディレクトリを読めませんでした。", e);
            return new SchematicFiles(sizes, true);
        }
        return new SchematicFiles(sizes, false);
    }

    /** 同じ理由の警告を繰り返さない。理由が変われば再度出す。 */
    private void logOnce(SchemDepotUnavailable reason, String message, Throwable cause) {
        if (lastLoggedReason == reason) {
            return;
        }
        lastLoggedReason = reason;
        if (cause != null) {
            logger.log(Level.WARNING, message, cause);
        } else {
            logger.info(message);
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.schemdepot.SchemDepotReaderTest'`
Expected: PASS（9 テスト）

- [ ] **Step 5: コミット**

```bash
git add plugin/src/main/java/dev/warasugi/warp/schemdepot plugin/src/test/java/dev/warasugi/warp/schemdepot
git commit -m "feat: SchemDepotレジストリの読み取り専用リーダーと集計を追加"
```

---

## Task 4: 絶対制約の回帰テスト

設計書 §2 の検証方法をテストとして固定する。このタスクは**新しい機能を足さない**。将来の変更で不変条件が壊れたときに落ちる網を張る。

**Files:**
- Test: `plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotReadOnlyTest.java`

**Interfaces:**
- Consumes: Task 3 の `SchemDepotReader`、Task 2 の `SchemDepotTestFixture`
- Produces: なし（テストのみ）

- [ ] **Step 1: 不変性テストと静的検査テストを書く**

```java
package dev.warasugi.warp.schemdepot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 設計書 §2「既存アセットを絶対に失わない」の回帰テスト。
 *
 * ここが落ちたら、SchemDepot のデータを壊しうる変更が入ったということ。
 * テストを緩めるのではなく、変更のほうを直すこと。
 */
class SchemDepotReadOnlyTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";

    private static final Path SOURCE_DIR =
            Path.of("src/main/java/dev/warasugi/warp/schemdepot");

    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "VACUUM",
            "executeUpdate", "Files.delete", "Files.deleteIfExists", "Files.move",
            "Files.write", "Files.copy", "Files.newOutputStream", "setReadOnly(false)");

    @Test
    void readingDoesNotModifyDatabaseOrSchematics(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "House", UUID_A, "alice", 1000L, 2, 3, 4, 100)
                .addAsset("a-2", "Gone", UUID_A, "alice", 2000L, 1, 1, 1, -1)
                .addOrphanFile("stray.schem", 70);

        Path root = plugins.resolve("SchemDepot");
        Map<String, String> before = fingerprint(root);

        var reader = new SchemDepotReader(plugins, LOG);
        for (int i = 0; i < 3; i++) {
            assertTrue(reader.read().available());
        }

        assertEquals(before, fingerprint(root),
                "読み取りだけで SchemDepot のファイルが変化した");
    }

    @Test
    void productionSourcesContainNoMutatingOperations() throws Exception {
        assertTrue(Files.isDirectory(SOURCE_DIR),
                "ソースディレクトリが見つからない: " + SOURCE_DIR.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String code = stripComment(line);
                    for (String token : FORBIDDEN_TOKENS) {
                        if (code.contains(token)) {
                            violations.add(file.getFileName() + ":" + (i + 1) + " → " + token);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "SchemDepot 連携のプロダクションコードに書き込み系の操作がある: " + violations);
    }

    /** 行コメント部分を落とす。禁止語をドキュメントとして書けるようにするため。 */
    private static String stripComment(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return "";
        }
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    /** ディレクトリ配下の相対パス → SHA-256。内容とファイル構成の両方を捕まえる。 */
    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                result.put(root.relativize(file).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))));
            }
        }
        return result;
    }
}
```

- [ ] **Step 2: テストを実行して通ることを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.schemdepot.SchemDepotReadOnlyTest'`
Expected: PASS（2 テスト）

`readingDoesNotModifyDatabaseOrSchematics` が「`assets.db-shm` / `assets.db-wal` が増えた」で落ちる場合、それは read-only 接続が WAL の共有メモリを触っている証拠なので、テストを緩めずに §5.4 の扱いを再検討すること。フィクスチャは WAL を有効にしていないため通常は発生しない。

- [ ] **Step 3: 壊したら落ちることを確認（一時的な検証）**

`SchemDepotReader.selectAll` の SQL 文字列を一時的に `"SELECT id FROM assets; DELETE FROM assets"` のような文言に書き換え、`productionSourcesContainNoMutatingOperations` が FAIL することを目視で確認してから元に戻す。

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.schemdepot.SchemDepotReadOnlyTest'`
Expected: 書き換え時 FAIL → 戻して PASS

- [ ] **Step 4: コミット**

```bash
git add plugin/src/test/java/dev/warasugi/warp/schemdepot/SchemDepotReadOnlyTest.java
git commit -m "test: SchemDepotのデータを変更しないことの回帰テストを追加"
```

---

## Task 5: HTTP ハンドラ

**Files:**
- Create: `plugin/src/main/java/dev/warasugi/warp/web/handlers/SchemDepotHandler.java`
- Test: `plugin/src/test/java/dev/warasugi/warp/web/handlers/SchemDepotHandlerTest.java`

**Interfaces:**
- Consumes: Task 3 の `SchemDepotReader` と `SchemDepotReader.Result`、Task 2 の DTO 群と `SchemDepotTestFixture`
- Produces:
  - `SchemDepotHandler(SchemDepotReader reader)` — `RouteRegistrar` 実装
  - `GET /api/schemdepot/status` → `record StatusDto(boolean available, String reason)`
  - `GET /api/schemdepot/assets` → `record AssetsDto(int total, int page, int pageSize, List<SchemDepotAsset> items)`
  - `GET /api/schemdepot/stats` → `record StatsDto(int totalCount, long totalBytes, int authorCount, List<AuthorStat> authors, Integrity integrity)`
  - `static final int PAGE_SIZE = 50`

- [ ] **Step 1: 失敗するテストを書く**

`plugin/src/test/java/dev/warasugi/warp/web/handlers/SchemDepotHandlerTest.java`:

```java
package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.schemdepot.SchemDepotReader;
import dev.warasugi.warp.schemdepot.SchemDepotTestFixture;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SchemDepotHandlerTest {

    private static final Logger LOG = Logger.getLogger("test");

    private Javalin appFor(Path plugins) {
        Javalin app = Javalin.create();
        new SchemDepotHandler(new SchemDepotReader(plugins, LOG)).register(app);
        return app;
    }

    @Test
    void statusReportsUnavailableWhenNotInstalled(@TempDir Path plugins) {
        JavalinTest.test(appFor(plugins), (server, client) -> {
            var response = client.get("/api/schemdepot/status");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("\"available\":false"), body);
            assertTrue(body.contains("not_installed"), body);
        });
    }

    @Test
    void statusReportsAvailableWhenInstalled(@TempDir Path plugins) throws Exception {
        seed(plugins, 1);
        JavalinTest.test(appFor(plugins), (server, client) -> {
            var response = client.get("/api/schemdepot/status");
            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("\"available\":true"));
        });
    }

    @Test
    void assetsReturnsItemsAndTotal(@TempDir Path plugins) throws Exception {
        seed(plugins, 3);
        JavalinTest.test(appFor(plugins), (server, client) -> {
            var response = client.get("/api/schemdepot/assets");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("\"total\":3"), body);
            assertTrue(body.contains("asset-0"), body);
        });
    }

    @Test
    void assetsFiltersByQuery(@TempDir Path plugins) throws Exception {
        seed(plugins, 3);
        JavalinTest.test(appFor(plugins), (server, client) -> {
            var response = client.get("/api/schemdepot/assets?q=asset-1");
            String body = response.body().string();
            assertTrue(body.contains("\"total\":1"), body);
            assertTrue(body.contains("asset-1"), body);
            assertFalse(body.contains("asset-2"), body);
        });
    }

    @Test
    void statsReturnsAggregates(@TempDir Path plugins) throws Exception {
        seed(plugins, 2);
        JavalinTest.test(appFor(plugins), (server, client) -> {
            var response = client.get("/api/schemdepot/stats");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("\"totalCount\":2"), body);
            assertTrue(body.contains("\"authorCount\":1"), body);
        });
    }

    @Test
    void statsIsUnavailableWhenNotInstalled(@TempDir Path plugins) {
        JavalinTest.test(appFor(plugins), (server, client) -> {
            var response = client.get("/api/schemdepot/stats");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("\"totalCount\":0"), body);
        });
    }

    @Test
    void writeMethodsAreNotRegistered(@TempDir Path plugins) throws Exception {
        seed(plugins, 1);
        JavalinTest.test(appFor(plugins), (server, client) -> {
            assertEquals(404, client.post("/api/schemdepot/assets", "{}").code());
            assertEquals(404, client.delete("/api/schemdepot/assets").code());
        });
    }

    /** アセットを count 件用意する (作者は1人・各10バイト)。 */
    private static void seed(Path plugins, int count) throws IOException, SQLException {
        SchemDepotTestFixture fixture = SchemDepotTestFixture.create(plugins);
        for (int i = 0; i < count; i++) {
            String id = "asset-" + i;
            fixture.addAsset(id, id, "11111111-1111-1111-1111-111111111111", "alice",
                    1000L + i, 1, 1, 1, 10);
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.web.handlers.SchemDepotHandlerTest'`
Expected: コンパイルエラー（`SchemDepotHandler` が存在しない）

- [ ] **Step 3: `SchemDepotHandler` を実装**

```java
package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.schemdepot.AuthorStat;
import dev.warasugi.warp.schemdepot.Integrity;
import dev.warasugi.warp.schemdepot.SchemDepotAsset;
import dev.warasugi.warp.schemdepot.SchemDepotReader;
import dev.warasugi.warp.schemdepot.SchemDepotSnapshot;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * SchemDepot のレジストリを閲覧するための読み取り専用エンドポイント。
 *
 * GET しか登録しない (設計書 §2-6)。SchemDepot が未導入でもルートは登録し、
 * status が available=false を返すことでフロント側が項目ごと隠す。
 */
public class SchemDepotHandler implements RouteRegistrar {

    static final int PAGE_SIZE = 50;

    private final SchemDepotReader reader;

    public SchemDepotHandler(SchemDepotReader reader) {
        this.reader = reader;
    }

    public record StatusDto(boolean available, String reason) {}

    public record AssetsDto(int total, int page, int pageSize, List<SchemDepotAsset> items) {}

    public record StatsDto(int totalCount, long totalBytes, int authorCount,
                           List<AuthorStat> authors, Integrity integrity) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/schemdepot/status", this::getStatus);
        app.get("/api/schemdepot/assets", this::getAssets);
        app.get("/api/schemdepot/stats", this::getStats);
    }

    public void getStatus(Context ctx) {
        SchemDepotReader.Result result = reader.read();
        ctx.json(new StatusDto(result.available(),
                result.available() ? null : result.reason().code()));
    }

    public void getAssets(Context ctx) {
        SchemDepotReader.Result result = reader.read();
        int page = PagingParams.page(ctx);
        if (!result.available()) {
            ctx.json(new AssetsDto(0, page, PAGE_SIZE, List.of()));
            return;
        }

        List<SchemDepotAsset> filtered = filter(result.snapshot().assets(), ctx.queryParam("q"));
        filtered = sort(filtered, ctx.queryParam("sort"), ctx.queryParam("order"));

        int from = Math.min(page * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        ctx.json(new AssetsDto(filtered.size(), page, PAGE_SIZE, filtered.subList(from, to)));
    }

    public void getStats(Context ctx) {
        SchemDepotReader.Result result = reader.read();
        if (!result.available()) {
            ctx.json(new StatsDto(0, 0L, 0, List.of(), Integrity.empty()));
            return;
        }
        SchemDepotSnapshot snap = result.snapshot();
        ctx.json(new StatsDto(snap.totalCount(), snap.totalBytes(), snap.authorCount(),
                snap.authors(), snap.integrity()));
    }

    private static List<SchemDepotAsset> filter(List<SchemDepotAsset> assets, String query) {
        if (query == null || query.isBlank()) {
            return assets;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return assets.stream()
                .filter(a -> a.name().toLowerCase(Locale.ROOT).contains(needle)
                        || a.authorName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private static List<SchemDepotAsset> sort(List<SchemDepotAsset> assets, String sort, String order) {
        Comparator<SchemDepotAsset> comparator = switch (sort == null ? "created" : sort) {
            case "name" -> Comparator.comparing(a -> a.name().toLowerCase(Locale.ROOT));
            case "size" -> Comparator.comparingLong(SchemDepotAsset::bytes);
            case "author" -> Comparator.comparing(a -> a.authorName().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingLong(SchemDepotAsset::createdAt);
        };
        // 同着の並びを安定させる
        comparator = comparator.thenComparing(SchemDepotAsset::id);
        if (!"asc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        return assets.stream().sorted(comparator).toList();
    }
}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `cd plugin && ./gradlew test --tests 'dev.warasugi.warp.web.handlers.SchemDepotHandlerTest'`
Expected: PASS（7 テスト）

- [ ] **Step 5: コミット**

```bash
git add plugin/src/main/java/dev/warasugi/warp/web/handlers/SchemDepotHandler.java plugin/src/test/java/dev/warasugi/warp/web/handlers/SchemDepotHandlerTest.java
git commit -m "feat: SchemDepot閲覧用のGETエンドポイントを追加"
```

---

## Task 6: WarpPlugin への配線

**Files:**
- Modify: `plugin/src/main/java/dev/warasugi/warp/WarpPlugin.java`（`initWeb`、現状 124-145 行付近）

**Interfaces:**
- Consumes: Task 3 の `SchemDepotReader`、Task 5 の `SchemDepotHandler`
- Produces: なし（配線のみ）

- [ ] **Step 1: import を追加**

`WarpPlugin.java` の import 節に1行足す（`dev.warasugi.warp.web.handlers.*` は既にワイルドカードなのでハンドラ側の import は不要）:

```java
import dev.warasugi.warp.schemdepot.SchemDepotReader;
```

- [ ] **Step 2: `initWeb` にリーダーとハンドラを追加**

`PluginHandler pluginHandler = new PluginHandler(this, db.auditRepo());` の直後に追加する:

```java
        // SchemDepot はオプション扱い。plugins/ 配下を読むだけで、依存も参照も持たない。
        // 未導入なら status が available=false を返し、フロント側で項目ごと隠れる。
        SchemDepotReader schemDepotReader =
                new SchemDepotReader(getDataFolder().toPath().getParent(), getLogger());
        SchemDepotHandler schemDepotHandler = new SchemDepotHandler(schemDepotReader);
```

- [ ] **Step 3: ハンドラリストに登録**

`List<RouteRegistrar> handlers = List.of(...)` を次のように変える（`pluginHandler` の後、`wsHandler` の前に挿入）:

```java
        List<RouteRegistrar> handlers = List.of(
                auth.authHandler(), statusHandler, playerHandler, banHandler,
                chatHandler, consoleHandler, logHandler, historyHandler, auditHandler,
                pluginHandler, schemDepotHandler, wsHandler);
```

- [ ] **Step 4: ビルドと全テストを実行**

Run: `cd plugin && ./gradlew build`
Expected: BUILD SUCCESSFUL、既存テストを含め全件 PASS

`getDataFolder().toPath().getParent()` は `plugins/WARP` の親 = `plugins/` を指す。これがリーダーに渡す `pluginsDir` になる。

- [ ] **Step 5: コミット**

```bash
git add plugin/src/main/java/dev/warasugi/warp/WarpPlugin.java
git commit -m "feat: SchemDepot連携ハンドラをWebサーバーに登録"
```

---

## Task 7: フロントエンドの API クライアントと整形ユーティリティ

**Files:**
- Create: `frontend/src/lib/format.ts`
- Modify: `frontend/src/lib/api.ts`（末尾の `api` オブジェクト）

**Interfaces:**
- Consumes: Task 5 のエンドポイント
- Produces:
  - `formatBytes(bytes: number): string`
  - `api.schemDepotStatus()` / `api.schemDepotAssets(page, q?, sort?, order?)` / `api.schemDepotStats()`

- [ ] **Step 1: `format.ts` を作る**

```ts
const UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB'] as const

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${UNITS[unit]}`
}
```

- [ ] **Step 2: `api.ts` に3メソッドを足す**

`selfUpdate: () => request<unknown>('/api/plugins/self-update'),` の直後、`}` の直前に追加する:

```ts
  schemDepotStatus: () =>
    request<unknown>('/api/schemdepot/status'),
  schemDepotAssets: (page = 0, q?: string, sort?: string, order?: string) =>
    request<unknown>(
      `/api/schemdepot/assets?page=${page}` +
        `${q ? `&q=${encodeURIComponent(q)}` : ''}` +
        `${sort ? `&sort=${encodeURIComponent(sort)}` : ''}` +
        `${order ? `&order=${encodeURIComponent(order)}` : ''}`
    ),
  schemDepotStats: () =>
    request<unknown>('/api/schemdepot/stats'),
```

- [ ] **Step 3: 型チェック**

Run: `cd frontend && npx tsc -b --noEmit`
Expected: エラーなし

- [ ] **Step 4: コミット**

```bash
git add frontend/src/lib/format.ts frontend/src/lib/api.ts
git commit -m "feat: SchemDepot APIクライアントと容量表示ユーティリティを追加"
```

---

## Task 8: SchemDepot ページとナビゲーション

**Files:**
- Create: `frontend/src/hooks/useSchemDepot.ts`
- Create: `frontend/src/pages/SchemDepot.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/layout/Sidebar.tsx`

**Interfaces:**
- Consumes: Task 7 の `api.schemDepot*` と `formatBytes`
- Produces:
  - `SchemDepotProvider`（`App.tsx` で `Layout` を包む）と `useSchemDepotStatus(): { available: boolean; reason: string | null; loading: boolean }`
  - ルート `/schemdepot`

- [ ] **Step 1: 共有フックを作る**

Sidebar と Dashboard の両方が status を必要とするため、Context で1回だけ取得する。

`frontend/src/hooks/useSchemDepot.ts`:

```ts
import { createContext, createElement, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import api from '../lib/api'

interface SchemDepotStatus {
  available: boolean
  reason: string | null
  loading: boolean
}

const initial: SchemDepotStatus = { available: false, reason: null, loading: true }

const SchemDepotContext = createContext<SchemDepotStatus>(initial)

export function SchemDepotProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SchemDepotStatus>(initial)

  useEffect(() => {
    let cancelled = false
    api
      .schemDepotStatus()
      .then(d => {
        if (cancelled) return
        const s = d as { available: boolean; reason: string | null }
        setStatus({ available: s.available, reason: s.reason, loading: false })
      })
      .catch(() => {
        if (!cancelled) setStatus({ available: false, reason: null, loading: false })
      })
    return () => {
      cancelled = true
    }
  }, [])

  return createElement(SchemDepotContext.Provider, { value: status }, children)
}

export function useSchemDepotStatus(): SchemDepotStatus {
  return useContext(SchemDepotContext)
}
```

- [ ] **Step 2: ページを作る**

`frontend/src/pages/SchemDepot.tsx`:

```tsx
import { useEffect, useState } from 'react'
import api from '../lib/api'
import { formatBytes } from '../lib/format'
import { useSchemDepotStatus } from '../hooks/useSchemDepot'

interface Asset {
  id: string
  name: string
  authorUuid: string
  authorName: string
  createdAt: number
  updatedAt: number
  sizeX: number
  sizeY: number
  sizeZ: number
  volume: number
  bytes: number
  fileMissing: boolean
}

interface AuthorStat {
  uuid: string
  name: string
  count: number
  bytes: number
  share: number
}

interface Stats {
  totalCount: number
  totalBytes: number
  authorCount: number
  authors: AuthorStat[]
  integrity: {
    missingFiles: { id: string; name: string; file: string }[]
    orphanFiles: { file: string; bytes: number }[]
    orphanBytes: number
    schematicsUnreadable: boolean
  }
}

interface AssetsPage {
  total: number
  page: number
  pageSize: number
  items: Asset[]
}

const SORTS = [
  { key: 'created', label: '登録日' },
  { key: 'name', label: '名前' },
  { key: 'size', label: '容量' },
  { key: 'author', label: '作者' },
] as const

export default function SchemDepot() {
  const status = useSchemDepotStatus()
  const [stats, setStats] = useState<Stats | null>(null)
  const [page, setPage] = useState(0)
  const [assets, setAssets] = useState<AssetsPage | null>(null)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<string>('created')
  const [order, setOrder] = useState<'asc' | 'desc'>('desc')

  useEffect(() => {
    if (!status.available) return
    api.schemDepotStats().then(d => setStats(d as Stats))
  }, [status.available])

  useEffect(() => {
    if (!status.available) return
    api.schemDepotAssets(page, query, sort, order).then(d => setAssets(d as AssetsPage))
  }, [status.available, page, query, sort, order])

  if (status.loading) {
    return <div className="p-6 text-gray-400">読み込み中…</div>
  }

  if (!status.available) {
    return (
      <div className="p-6 space-y-2">
        <h2 className="text-lg font-semibold text-white">SchemDepot</h2>
        <p className="text-sm text-gray-400">
          SchemDepot が見つかりません（{status.reason ?? 'not_installed'}）
        </p>
      </div>
    )
  }

  function toggleSort(key: string) {
    if (sort === key) {
      setOrder(o => (o === 'asc' ? 'desc' : 'asc'))
    } else {
      setSort(key)
      setOrder('desc')
    }
    setPage(0)
  }

  const totalPages = assets ? Math.max(1, Math.ceil(assets.total / assets.pageSize)) : 1
  const integrity = stats?.integrity
  const hasIntegrityIssue =
    !!integrity &&
    (integrity.missingFiles.length > 0 ||
      integrity.orphanFiles.length > 0 ||
      integrity.schematicsUnreadable)

  return (
    <div className="p-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-white">SchemDepot</h2>
        <p className="text-sm text-gray-400 mt-1">登録済みアセットと容量の内訳（閲覧のみ）</p>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400">アセット数</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">{stats?.totalCount ?? '—'}</p>
        </div>
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400">総容量</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">
            {stats ? formatBytes(stats.totalBytes) : '—'}
          </p>
        </div>
        <div className="rounded-xl p-4 bg-warp-panel">
          <p className="text-xs text-gray-400">作者数</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">{stats?.authorCount ?? '—'}</p>
        </div>
      </div>

      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <div className="px-4 py-3 border-b border-white/5 text-sm text-gray-300">作者別</div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              <th className="px-4 py-3">作者</th>
              <th className="px-4 py-3 w-24 text-right">件数</th>
              <th className="px-4 py-3 w-28 text-right">容量</th>
              <th className="px-4 py-3 w-48">占有率</th>
            </tr>
          </thead>
          <tbody>
            {(stats?.authors ?? []).map(a => (
              <tr key={a.uuid} className="border-b border-white/3">
                <td className="px-4 py-3 text-white">{a.name}</td>
                <td className="px-4 py-3 text-right text-gray-300">{a.count}</td>
                <td className="px-4 py-3 text-right text-gray-300">{formatBytes(a.bytes)}</td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2">
                    <div className="flex-1 h-1.5 rounded-full bg-white/10 overflow-hidden">
                      <div
                        className="h-full bg-warp-accent"
                        style={{ width: `${Math.round(a.share * 100)}%` }}
                      />
                    </div>
                    <span className="text-xs text-gray-400 w-10 text-right">
                      {Math.round(a.share * 100)}%
                    </span>
                  </div>
                </td>
              </tr>
            ))}
            {stats && stats.authors.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-500">
                  アセットがありません
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="rounded-xl overflow-hidden bg-warp-panel">
        <div className="px-4 py-3 border-b border-white/5 flex items-center justify-between gap-4">
          <span className="text-sm text-gray-300">アセット一覧</span>
          <input
            value={query}
            onChange={e => {
              setQuery(e.target.value)
              setPage(0)
            }}
            placeholder="名前・作者で検索"
            className="px-3 py-1.5 rounded-lg bg-black/20 text-sm text-white placeholder-gray-500 outline-none focus:ring-1 focus:ring-warp-accent"
          />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-white/5">
              {SORTS.map(s => (
                <th
                  key={s.key}
                  onClick={() => toggleSort(s.key)}
                  className="px-4 py-3 cursor-pointer select-none hover:text-white"
                >
                  {s.label}
                  {sort === s.key && <span className="ml-1">{order === 'asc' ? '▲' : '▼'}</span>}
                </th>
              ))}
              <th className="px-4 py-3">寸法</th>
            </tr>
          </thead>
          <tbody>
            {(assets?.items ?? []).map(a => (
              <tr key={a.id} className="border-b border-white/3">
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {new Date(a.createdAt).toLocaleString()}
                </td>
                <td className="px-4 py-3 text-white">
                  {a.name}
                  {a.fileMissing && (
                    <span className="ml-2 text-xs text-amber-400">ファイル欠損</span>
                  )}
                </td>
                <td className="px-4 py-3 text-gray-300">{formatBytes(a.bytes)}</td>
                <td className="px-4 py-3 text-gray-300">{a.authorName}</td>
                <td className="px-4 py-3 text-gray-400 text-xs font-mono">
                  {a.sizeX}×{a.sizeY}×{a.sizeZ}
                </td>
              </tr>
            ))}
            {assets && assets.items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                  該当するアセットがありません
                </td>
              </tr>
            )}
          </tbody>
        </table>
        {assets && assets.total > assets.pageSize && (
          <div className="px-4 py-3 flex items-center justify-between text-sm border-t border-white/5">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-3 py-1 rounded-lg bg-white/5 text-gray-300 disabled:opacity-30"
            >
              前へ
            </button>
            <span className="text-gray-400 text-xs">
              {page + 1} / {totalPages}
            </span>
            <button
              onClick={() => setPage(p => (p + 1 < totalPages ? p + 1 : p))}
              disabled={page + 1 >= totalPages}
              className="px-3 py-1 rounded-lg bg-white/5 text-gray-300 disabled:opacity-30"
            >
              次へ
            </button>
          </div>
        )}
      </div>

      {hasIntegrityIssue && (
        <div className="rounded-xl overflow-hidden bg-warp-panel border border-amber-500/30">
          <div className="px-4 py-3 border-b border-white/5 text-sm text-amber-400">
            不整合（表示のみ・WARPからは削除しません）
          </div>
          <div className="px-4 py-3 space-y-3 text-sm">
            {integrity.schematicsUnreadable && (
              <p className="text-gray-300">
                schematics ディレクトリを読めないため、容量を集計できていません
              </p>
            )}
            {integrity.missingFiles.length > 0 && (
              <div>
                <p className="text-xs text-gray-400 mb-1">
                  ファイルが見つからないアセット（{integrity.missingFiles.length}件）
                </p>
                <ul className="space-y-0.5">
                  {integrity.missingFiles.map(m => (
                    <li key={m.id} className="text-gray-300">
                      {m.name} <span className="text-gray-500 font-mono text-xs">{m.file}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {integrity.orphanFiles.length > 0 && (
              <div>
                <p className="text-xs text-gray-400 mb-1">
                  参照されていないファイル（{integrity.orphanFiles.length}件・
                  {formatBytes(integrity.orphanBytes)}）
                </p>
                <ul className="space-y-0.5">
                  {integrity.orphanFiles.map(o => (
                    <li key={o.file} className="text-gray-300 font-mono text-xs">
                      {o.file} <span className="text-gray-500">{formatBytes(o.bytes)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 3: `App.tsx` にルートと Provider を追加**

`import Plugins from './pages/Plugins'` の直後に追加:

```tsx
import SchemDepot from './pages/SchemDepot'
import { SchemDepotProvider } from './hooks/useSchemDepot'
```

`WsProvider` の内側を `SchemDepotProvider` で包む:

```tsx
              <WsProvider>
                <SchemDepotProvider>
                  <Layout />
                </SchemDepotProvider>
              </WsProvider>
```

`<Route path="plugins" element={<Plugins />} />` の直後に追加:

```tsx
          <Route path="schemdepot" element={<SchemDepot />} />
```

- [ ] **Step 4: `Sidebar.tsx` を条件表示にする**

`links` 配列から `SchemDepot` は外しておき、available のときだけ足す:

```tsx
import { NavLink } from 'react-router-dom'
import { useSchemDepotStatus } from '../../hooks/useSchemDepot'

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/terminal', label: 'Terminal' },
  { to: '/players', label: 'Players' },
  { to: '/chat', label: 'Chat' },
  { to: '/bans', label: 'Bans' },
  { to: '/logs', label: 'Logs' },
  { to: '/history', label: 'History' },
  { to: '/audit', label: 'Audit' },
  { to: '/plugins', label: 'Plugins' },
]

export default function Sidebar() {
  const schemDepot = useSchemDepotStatus()
  const items = schemDepot.available
    ? [...links, { to: '/schemdepot', label: 'SchemDepot' }]
    : links

  return (
    <aside
      className="w-48 h-screen flex flex-col py-6 px-3 shrink-0 bg-warp-sidebar border-r border-white/5"
    >
      <div className="mb-8 px-3">
        <span className="text-xl font-bold text-warp-accent">WARP</span>
      </div>
      <nav className="flex flex-col gap-1">
        {items.map(link => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.to === '/'}
            className={({ isActive }) =>
              `px-3 py-2 rounded-lg text-sm transition-colors ${
                isActive
                  ? 'text-white font-medium bg-warp-accent/15'
                  : 'text-gray-400 hover:text-white'
              }`
            }
          >
            {link.label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
```

- [ ] **Step 5: 型チェックとビルド**

Run: `cd frontend && npx tsc -b --noEmit && npm run build`
Expected: エラーなし、`dist/` が生成される

- [ ] **Step 6: コミット**

```bash
git add frontend/src/hooks frontend/src/pages/SchemDepot.tsx frontend/src/App.tsx frontend/src/components/layout/Sidebar.tsx
git commit -m "feat: SchemDepotアセット一覧ページとナビゲーションを追加"
```

---

## Task 9: ダッシュボードのサマリーカード

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`

**Interfaces:**
- Consumes: Task 7 の `api.schemDepotStats` / `formatBytes`、Task 8 の `useSchemDepotStatus`
- Produces: なし（最終タスク）

- [ ] **Step 1: import を追加**

```tsx
import { Link } from 'react-router-dom'
import api from '../lib/api'
import { formatBytes } from '../lib/format'
import { useSchemDepotStatus } from '../hooks/useSchemDepot'
```

- [ ] **Step 2: state と取得処理を追加**

`const [tpsHistory, setTpsHistory] = useState<number[]>([])` の直後:

```tsx
  const schemDepot = useSchemDepotStatus()
  const [schemStats, setSchemStats] = useState<{ totalCount: number; totalBytes: number } | null>(null)

  useEffect(() => {
    if (!schemDepot.available) return
    api.schemDepotStats().then(d => setSchemStats(d as { totalCount: number; totalBytes: number }))
  }, [schemDepot.available])
```

- [ ] **Step 3: カードを描画**

TPS チャートのブロックの直前（`{tpsHistory.length > 0 && (` の前）に挿入:

```tsx
      {schemDepot.available && schemStats && (
        <Link
          to="/schemdepot"
          className="block rounded-xl p-4 bg-warp-panel hover:bg-warp-panel/80 transition-colors"
        >
          <p className="text-xs text-gray-400">SchemDepot</p>
          <p className="text-2xl font-bold mt-1 text-warp-accent">
            {schemStats.totalCount}
            <span className="text-sm font-normal text-gray-400 ml-2">アセット</span>
            <span className="text-sm font-normal text-gray-400 ml-3">
              {formatBytes(schemStats.totalBytes)}
            </span>
          </p>
        </Link>
      )}
```

- [ ] **Step 4: 型チェックとビルド**

Run: `cd frontend && npx tsc -b --noEmit && npm run build`
Expected: エラーなし

- [ ] **Step 5: プラグイン全体をビルド**

Run: `cd plugin && ./gradlew build`
Expected: BUILD SUCCESSFUL、全テスト PASS、`plugin/build/libs/` に shadow jar

- [ ] **Step 6: コミット**

```bash
git add frontend/src/pages/Dashboard.tsx
git commit -m "feat: ダッシュボードにSchemDepotのサマリーカードを追加"
```

---

## 実機での確認手順

1. `plugin/build/libs/` の shadow jar をサーバーの `plugins/` に置いて再起動
2. `plugins/SchemDepot/` が**無い**状態で WARP を起動し、サイドバーに SchemDepot が出ないこと、他機能が従来どおり動くことを確認
3. `plugins/SchemDepot/` を配置して再起動し、サイドバーに項目が出て一覧・作者別集計・総容量が表示されることを確認
4. **確認前後で `plugins/SchemDepot/assets.db` と `schematics/` の中身が変わっていないこと**を確認する:

```bash
# 画面を触る前
find plugins/SchemDepot -type f -exec sha256sum {} + | sort > /tmp/before.txt
# ダッシュボードでSchemDepotページを一通り操作したあと
find plugins/SchemDepot -type f -exec sha256sum {} + | sort > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt   # 差分が出ないこと
```

`-wal` / `-shm` は SchemDepot 自身の稼働で変化しうるため、厳密に見るなら SchemDepot を無効化した状態で比較する。

---

## Self-Review

**Spec coverage:**

| 設計書のセクション | 実装タスク |
|---|---|
| §2 絶対制約（6項目 + 検証方法） | Global Constraints、Task 4 |
| §4 新規クラス構成 | Task 1〜3, 5 |
| §4 既存コードへの変更 | Task 6 |
| §5.1 パス解決とディレクトリ名の検証 | Task 1 |
| §5.2 スキーマバージョン検査 | Task 3 |
| §5.3 read-only 接続 | Task 3 |
| §5.4 WAL 制約と degrade | Task 3（`OPEN_FAILED`）、Task 4 Step 2 の注記 |
| §6 API 3本 | Task 5 |
| §7 フロントエンド | Task 7〜9 |
| §8 エラー処理と degrade の表 | Task 3（`logOnce`、個別 `Files.size()` 失敗の扱い）、Task 5（未導入時の空レスポンス） |
| §9 TTL キャッシュと `ReentrantLock` | Task 3 |
| §10 パスを返さない / 監査ログなし | Task 5（DTO に `schematic_file` のファイル名のみ、`AuditRepository` 不使用） |
| §11 テスト方針 4項目 | Task 1, 3, 4, 5 |

ギャップなし。

**Placeholder scan:** 「TBD」「後で実装」「適切なエラー処理を追加」「Task N と同様」の類は含まれていない。全ステップに実行可能なコードとコマンドを記載済み。

**Type consistency:**
- `SchemDepotUnavailable.code()` — Task 1 で定義、Task 3・5 で使用 ✓
- `SchemDepotLocator.Result.available()` / `.paths()` / `.reason()` — Task 1 定義、Task 3 使用 ✓
- `SchemDepotReader.Result.available()` / `.snapshot()` / `.reason()` — Task 3 定義、Task 5 使用 ✓
- `Integrity.empty()` — Task 2 定義、Task 5 の未導入時レスポンスで使用 ✓
- `Integrity.MissingFile(id, name, file)` / `OrphanFile(file, bytes)` — Task 2 定義、Task 3 生成、Task 8 の TS 型と一致 ✓
- `SchemDepotAsset` の 12 フィールド — Task 2 定義、Task 3 生成、Task 8 の `interface Asset` と名前・型が一致 ✓
- `AuthorStat(uuid, name, count, bytes, share)` — Task 2 定義、Task 3 生成、Task 8 の `interface AuthorStat` と一致 ✓
- `AssetsDto(total, page, pageSize, items)` — Task 5 定義、Task 8 の `interface AssetsPage` と一致 ✓
- `useSchemDepotStatus()` — Task 8 定義、Task 8 の Sidebar と Task 9 の Dashboard で使用 ✓
- `formatBytes` — Task 7 定義、Task 8・9 で使用 ✓
- `SchemDepotTestFixture` の `addAsset` 引数順 — Task 2 定義、Task 3・4 の呼び出しと一致 ✓
