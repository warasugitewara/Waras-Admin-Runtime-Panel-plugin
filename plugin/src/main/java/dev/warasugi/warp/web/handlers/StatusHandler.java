package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.metrics.MetricsCollector;
import io.javalin.http.Context;

public class StatusHandler {
    private final MetricsCollector metrics;

    public StatusHandler(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    public void get(Context ctx) {
        ctx.json(metrics.getLatestSnapshotAsMap());
    }
}
