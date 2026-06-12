package dev.warasugi.warp.web;

import io.javalin.http.Context;

public final class PagingParams {
    // page * pageSize が int の範囲を超えないようにするための上限
    private static final int MAX_PAGE = 10_000_000;

    private PagingParams() {}

    public static int page(Context ctx) {
        return Math.min(Math.max(0, parseInt(ctx.queryParam("page"), 0)), MAX_PAGE);
    }

    private static int parseInt(String s, int def) {
        try {
            return s != null ? Integer.parseInt(s) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
