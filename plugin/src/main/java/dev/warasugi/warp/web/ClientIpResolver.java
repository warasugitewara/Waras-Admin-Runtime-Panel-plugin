package dev.warasugi.warp.web;

import io.javalin.http.Context;

/**
 * 127.0.0.1 バインド + cloudflared 経由のみという構成を前提に、
 * Cloudflare が付与する CF-Connecting-IP からクライアントの実IPを解決する。
 * ヘッダが無い場合（直接アクセス等）は ctx.ip() にフォールバックする。
 */
public class ClientIpResolver {
    public static String resolve(Context ctx) {
        String cfIp = ctx.header("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return cfIp;
        }
        return ctx.ip();
    }
}
