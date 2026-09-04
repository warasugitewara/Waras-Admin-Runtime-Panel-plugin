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
