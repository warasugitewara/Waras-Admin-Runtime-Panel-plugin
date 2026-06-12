package dev.warasugi.warp.web;

import io.javalin.http.Context;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 127.0.0.1 バインド + cloudflared 経由のみという構成を前提に、
 * Cloudflare が付与する CF-Connecting-IP からクライアントの実IPを解決する。
 * 直接アクセスを許す構成に変わった場合に CF-Connecting-IP の偽装で
 * RateLimiter/audit を欺けないよう、直接の接続元（ctx.ip()）が
 * ループバックの場合のみヘッダを信頼する。
 */
public class ClientIpResolver {
    public static String resolve(Context ctx) {
        String peer = ctx.ip();
        if (isLoopback(peer)) {
            String cfIp = ctx.header("CF-Connecting-IP");
            if (cfIp != null && !cfIp.isBlank()) {
                return cfIp;
            }
        }
        return peer;
    }

    private static boolean isLoopback(String ip) {
        // ip はリテラルなので getByName はDNS解決を伴わない。
        // ::ffff:127.0.0.1 のような IPv4-mapped IPv6 も判定できる。
        try {
            return InetAddress.getByName(ip).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
