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
