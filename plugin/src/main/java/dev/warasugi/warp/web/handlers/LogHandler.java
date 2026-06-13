package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.LogRepository;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;

public class LogHandler implements RouteRegistrar {
    private final LogRepository logs;

    public LogHandler(LogRepository logs) {
        this.logs = logs;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/logs", this::getLogs);
    }

    public void getLogs(Context ctx) throws Exception {
        String level = ctx.queryParam("level");
        String q = ctx.queryParam("q");
        int page = PagingParams.page(ctx);
        ctx.json(logs.query(level, q, 100, page * 100));
    }
}
