package dev.warasugi.warp.web.handlers;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.warasugi.warp.auth.JwtManager;
import dev.warasugi.warp.auth.RateLimiter;
import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.config.ConfigProvider;
import dev.warasugi.warp.config.PanelConfig;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthHandlerTest {

    @Test
    void loginBeforeSetupReturns503(@TempDir Path tempDir) throws Exception {
        JwtManager jwt = JwtManager.fromFile(tempDir.resolve("secret.key"), 8 * 3600_000L);
        ConfigProvider config = new ConfigProvider(new PanelConfig(new YamlConfiguration()));
        AuthHandler handler = new AuthHandler(null, jwt, new RateLimiter(5, 600_000L), config);

        Javalin app = Javalin.create();
        handler.register(app);

        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/api/auth/login", Map.of("code", "123456"));
            assertEquals(503, response.code());
        });
    }

    @Test
    void loginAfterSetupWithValidCodeIssuesSession(@TempDir Path tempDir) throws Exception {
        JwtManager jwt = JwtManager.fromFile(tempDir.resolve("secret.key"), 8 * 3600_000L);
        ConfigProvider config = new ConfigProvider(new PanelConfig(new YamlConfiguration()));
        AuthHandler handler = new AuthHandler(null, jwt, new RateLimiter(5, 600_000L), config);

        String secret = TotpManager.generateSecret();
        handler.setTotpManager(new TotpManager(secret));
        String code = new DefaultCodeGenerator(HashingAlgorithm.SHA1)
                .generate(secret, Math.floorDiv(new SystemTimeProvider().getTime(), 30));

        Javalin app = Javalin.create();
        handler.register(app);

        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/api/auth/login", Map.of("code", code));
            assertEquals(200, response.code());
            var cookies = response.headers("Set-Cookie");
            assertTrue(cookies.stream().anyMatch(c -> c.startsWith("jwt=")));
            assertTrue(cookies.stream().anyMatch(c -> c.startsWith("csrf_token=")));
        });
    }
}
