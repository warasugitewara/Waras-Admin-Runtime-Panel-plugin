package dev.warasugi.warp.web.middleware;

import dev.warasugi.warp.auth.JwtManager;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;

public class AuthMiddleware {
    private final JwtManager jwt;

    public AuthMiddleware(JwtManager jwt) {
        this.jwt = jwt;
    }

    public void handle(Context ctx) {
        // /api/auth/** は認証不要
        if (ctx.path().startsWith("/api/auth/")) return;
        // static コンテンツも認証不要
        if (!ctx.path().startsWith("/api")) return;
        // WS の認証は AdminWsHandler.onConnect で行う（before ハンドラは WS upgrade に発火しない）

        String token = ctx.cookie("jwt");
        if (token == null || !jwt.isValid(token)) {
            throw new HttpResponseException(401, "Unauthorized");
        }
    }
}
