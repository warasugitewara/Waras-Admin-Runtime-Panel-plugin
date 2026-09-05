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
