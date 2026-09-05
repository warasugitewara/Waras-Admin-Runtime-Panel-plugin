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
