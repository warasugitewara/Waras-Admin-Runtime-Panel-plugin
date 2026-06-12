package dev.warasugi.warp.web.middleware;

import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;

public class CsrfMiddleware {

    public void handle(Context ctx) {
        // 認証エンドポイントは CSRF 不要
        if (ctx.path().startsWith("/api/auth/")) return;
        // GET/HEAD/OPTIONS は CSRF 不要
        String method = ctx.method().name();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return;

        String csrfHeader = ctx.header("X-CSRF-Token");
        String csrfCookie = ctx.cookie("csrf_token");
        if (csrfHeader == null || csrfCookie == null || !csrfHeader.equals(csrfCookie)) {
            throw new HttpResponseException(403, "CSRF token mismatch");
        }
    }
}
