package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.metrics.MetricsCollector;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;

public class StatusHandler implements RouteRegistrar {
    private final MetricsCollector metrics;

    public StatusHandler(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/status", this::get);
    }

    @OpenApi(
            path = "/api/status",
            methods = HttpMethod.GET,
            summary = "サーバーの現在の状態",
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = MetricsCollector.Status.class)))
    public void get(Context ctx) {
        ctx.json(metrics.getLatestStatus());
    }
}
