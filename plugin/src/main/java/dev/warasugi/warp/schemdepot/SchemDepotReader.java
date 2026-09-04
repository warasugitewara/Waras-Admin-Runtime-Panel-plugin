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
