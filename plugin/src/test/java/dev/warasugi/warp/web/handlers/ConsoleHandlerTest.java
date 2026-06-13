package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.config.ConfigProvider;
import dev.warasugi.warp.config.PanelConfig;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleHandlerTest {

    @Test
    void rejectsBlockedCommand() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("console:\n  command-blocklist:\n    - stop\n");
        ConfigProvider config = new ConfigProvider(new PanelConfig(cfg));
        ConsoleHandler handler = new ConsoleHandler(null, config, null);

        Javalin app = Javalin.create();
        handler.register(app);

        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/api/console", Map.of("command", "stop"));
            assertEquals(403, response.code());
        });
    }
}
