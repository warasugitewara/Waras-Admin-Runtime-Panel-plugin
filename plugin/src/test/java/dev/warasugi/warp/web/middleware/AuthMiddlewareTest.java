package dev.warasugi.warp.web.middleware;

import dev.warasugi.warp.auth.JwtManager;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthMiddlewareTest {

    @Test
    void rejectsApiRequestWithoutJwtCookie(@TempDir Path tempDir) throws Exception {
        JwtManager jwt = JwtManager.fromFile(tempDir.resolve("secret.key"), 8 * 3600_000L);
        AuthMiddleware middleware = new AuthMiddleware(jwt);

        Javalin app = Javalin.create();
        app.before(middleware::handle);
        app.get("/api/players", ctx -> ctx.result("ok"));

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/players");
            assertEquals(401, response.code());
        });
    }

    @Test
    void allowsApiRequestWithValidJwtCookie(@TempDir Path tempDir) throws Exception {
        JwtManager jwt = JwtManager.fromFile(tempDir.resolve("secret.key"), 8 * 3600_000L);
        AuthMiddleware middleware = new AuthMiddleware(jwt);
        String token = jwt.issue();

        Javalin app = Javalin.create();
        app.before(middleware::handle);
        app.get("/api/players", ctx -> ctx.result("ok"));

        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/api/players", req -> req.header("Cookie", "jwt=" + token));
            assertEquals(200, response.code());
        });
    }
}
