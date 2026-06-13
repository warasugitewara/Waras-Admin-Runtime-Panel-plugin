package dev.warasugi.warp.web.middleware;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsrfMiddlewareTest {

    @Test
    void rejectsStateChangingRequestWithoutCsrfToken() {
        CsrfMiddleware middleware = new CsrfMiddleware();

        Javalin app = Javalin.create();
        app.before(middleware::handle);
        app.post("/api/console", ctx -> ctx.status(204));

        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/api/console", null);
            assertEquals(403, response.code());
        });
    }

    @Test
    void allowsStateChangingRequestWithMatchingCsrfToken() {
        CsrfMiddleware middleware = new CsrfMiddleware();

        Javalin app = Javalin.create();
        app.before(middleware::handle);
        app.post("/api/console", ctx -> ctx.status(204));

        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/api/console", null, req -> {
                req.header("X-CSRF-Token", "abc123");
                req.header("Cookie", "csrf_token=abc123");
            });
            assertEquals(204, response.code());
        });
    }
}
