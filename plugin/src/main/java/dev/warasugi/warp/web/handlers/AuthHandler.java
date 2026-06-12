package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.auth.JwtManager;
import dev.warasugi.warp.auth.RateLimiter;
import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.config.PanelConfig;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpResponseException;
import io.javalin.http.SameSite;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthHandler {
    private volatile TotpManager totp;
    private final JwtManager jwt;
    private final RateLimiter limiter;
    private final PanelConfig config;
    private final ConcurrentHashMap<String, Long> oneTimeTokens = new ConcurrentHashMap<>();

    public void setTotpManager(TotpManager totp) {
        this.totp = totp;
    }

    public AuthHandler(TotpManager totp, JwtManager jwt, RateLimiter limiter, PanelConfig config) {
        this.totp = totp;
        this.jwt = jwt;
        this.limiter = limiter;
        this.config = config;
    }

    public void login(Context ctx) {
        TotpManager currentTotp = this.totp;
        if (currentTotp == null) {
            throw new HttpResponseException(503, "TOTP not configured. Run /warp setup");
        }
        String ip = ctx.ip();
        if (!limiter.isAllowed(ip)) {
            throw new HttpResponseException(429, "Too many attempts");
        }
        record Body(String code) {}
        var body = ctx.bodyAsClass(Body.class);
        if (!currentTotp.verify(body.code())) {
            throw new HttpResponseException(401, "Invalid TOTP");
        }
        limiter.reset(ip);
        String token = jwt.issue();
        String csrf = UUID.randomUUID().toString();
        setJwtCookie(ctx, token);
        setCsrfCookie(ctx, csrf);
        ctx.json(Map.of("ok", true));
    }

    public void logout(Context ctx) {
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

    public String issueOneTimeToken() {
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + 5 * 60_000L;
        oneTimeTokens.put(token, expiry);
        return token;
    }

    public boolean isValidOneTimeToken(String token) {
        Long expiry = oneTimeTokens.remove(token);
        return expiry != null && System.currentTimeMillis() < expiry;
    }

    private void setJwtCookie(Context ctx, String token) {
        Cookie c = new Cookie("jwt", token);
        c.setHttpOnly(true);
        c.setSecure(true);
        c.setSameSite(SameSite.STRICT);
        c.setPath("/");
        c.setMaxAge(config.getSessionHours() * 3600);
        ctx.cookie(c);
    }

    private void setCsrfCookie(Context ctx, String csrf) {
        Cookie c = new Cookie("csrf_token", csrf);
        c.setHttpOnly(false);
        c.setSecure(true);
        c.setSameSite(SameSite.STRICT);
        c.setPath("/");
        c.setMaxAge(config.getSessionHours() * 3600);
        ctx.cookie(c);
    }
}
