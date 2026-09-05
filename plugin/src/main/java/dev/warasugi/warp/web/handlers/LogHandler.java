package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.LogRepository;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

public class LogHandler implements RouteRegistrar {
    private final LogRepository logs;

    public LogHandler(LogRepository logs) {
        this.logs = logs;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/logs", this::getLogs);
    }

    @OpenApi(
            path = "/api/logs",
            methods = HttpMethod.GET,
            summary = "サーバーログを検索する",
            queryParams = {
                    @OpenApiParam(name = "level", type = String.class),
                    @OpenApiParam(name = "q", type = String.class),
                    @OpenApiParam(name = "page", type = Integer.class)
            },
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = LogRepository.LogEntry[].class)))
    public void getLogs(Context ctx) throws Exception {
        String level = ctx.queryParam("level");
        String q = ctx.queryParam("q");
        int page = PagingParams.page(ctx);
        ctx.json(logs.query(level, q, 100, page * 100));
    }
}
