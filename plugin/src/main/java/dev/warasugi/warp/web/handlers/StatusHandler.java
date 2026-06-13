package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.metrics.MetricsCollector;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;

public class StatusHandler implements RouteRegistrar {
    private final MetricsCollector metrics;

    public StatusHandler(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/status", this::get);
    }

    public void get(Context ctx) {
        ctx.json(metrics.getLatestSnapshotAsMap());
    }
}
