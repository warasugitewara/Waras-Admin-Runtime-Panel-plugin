package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.auth.JwtManager;
import dev.warasugi.warp.auth.RateLimiter;
import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.config.ConfigProvider;
import dev.warasugi.warp.web.ClientIpResolver;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpResponseException;
import io.javalin.http.SameSite;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiRequired;
import io.javalin.openapi.OpenApiResponse;
import java.util.UUID;
import java.util.regex.Pattern;

public class AuthHandler implements RouteRegistrar {
    private volatile TotpManager totp;
    private final JwtManager jwt;
    private final RateLimiter limiter;
    private final ConfigProvider config;
    private static final Pattern TOTP_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    public void setTotpManager(TotpManager totp) {
        this.totp = totp;
    }

    public String getCurrentOtpCode() {
        TotpManager currentTotp = this.totp;
        return currentTotp == null ? null : currentTotp.getCurrentCode();
    }

    public AuthHandler(TotpManager totp, JwtManager jwt, RateLimiter limiter, ConfigProvider config) {
        this.totp = totp;
        this.jwt = jwt;
        this.limiter = limiter;
        this.config = config;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/auth/login", this::login);
        app.post("/api/auth/logout", this::logout);
    }

    @OpenApi(
            path = "/api/auth/login",
            methods = HttpMethod.POST,
            summary = "TOTPコードでログインする",
            requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = LoginRequest.class)),
            responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LoginResult.class)))
    public void login(Context ctx) {
        TotpManager currentTotp = this.totp;
        if (currentTotp == null) {
            throw new HttpResponseException(503, "TOTP not configured. Run /warp setup");
        }
        String ip = ClientIpResolver.resolve(ctx);
        if (!limiter.isAllowed(ip)) {
            throw new HttpResponseException(429, "Too many attempts");
        }
        var body = ctx.bodyAsClass(LoginRequest.class);
        if (body.code() == null || !TOTP_CODE_PATTERN.matcher(body.code()).matches()) {
            throw new HttpResponseException(400, "code must be a 6-digit number");
        }
        if (!currentTotp.verify(body.code())) {
            throw new HttpResponseException(401, "Invalid TOTP");
        }
        limiter.reset(ip);
        issueSession(ctx);
    }

    private void issueSession(Context ctx) {
        String token = jwt.issue();
        String csrf = UUID.randomUUID().toString();
        setJwtCookie(ctx, token);
        setCsrfCookie(ctx, csrf);
        ctx.json(new LoginResult(true));
    }

    @OpenApi(
            path = "/api/auth/logout",
            methods = HttpMethod.POST,
            summary = "ログアウトする",
            responses = @OpenApiResponse(status = "204"))
    public void logout(Context ctx) {
        jwt.revokeAll();
        Cookie jwtCookie = new Cookie("jwt", "");
        jwtCookie.setMaxAge(0);
        jwtCookie.setPath("/");
        ctx.cookie(jwtCookie);
        Cookie csrfCookie = new Cookie("csrf_token", "");
        csrfCookie.setMaxAge(0);
        csrfCookie.setPath("/");
        ctx.cookie(csrfCookie);
        ctx.status(204);
    }

    private void setJwtCookie(Context ctx, String token) {
        Cookie c = new Cookie("jwt", token);
        c.setHttpOnly(true);
        c.setSecure(true);
        c.setSameSite(SameSite.STRICT);
        c.setPath("/");
        c.setMaxAge(config.get().getSessionHours() * 3600);
        ctx.cookie(c);
    }

    private void setCsrfCookie(Context ctx, String csrf) {
        Cookie c = new Cookie("csrf_token", csrf);
        c.setHttpOnly(false);
        c.setSecure(true);
        c.setSameSite(SameSite.STRICT);
        c.setPath("/");
        c.setMaxAge(config.get().getSessionHours() * 3600);
        ctx.cookie(c);
    }

    public record LoginRequest(@OpenApiRequired String code) {}

    public record LoginResult(boolean ok) {}
}
