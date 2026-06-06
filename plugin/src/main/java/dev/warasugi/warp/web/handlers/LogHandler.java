package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.LogRepository;
import io.javalin.http.Context;

public class LogHandler {
    private final LogRepository logs;

    public LogHandler(LogRepository logs) {
        this.logs = logs;
    }

    public void getLogs(Context ctx) throws Exception {
        String level = ctx.queryParam("level");
        String q = ctx.queryParam("q");
        int page = parseInt(ctx.queryParam("page"), 0);
        ctx.json(logs.query(level, q, 100, page * 100));
    }

    private int parseInt(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }
}
