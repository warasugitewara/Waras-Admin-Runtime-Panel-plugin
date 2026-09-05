package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.HistoryRepository;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

import java.util.List;

public class HistoryHandler implements RouteRegistrar {
    private final HistoryRepository history;

    public HistoryHandler(HistoryRepository history) {
        this.history = history;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/history", this::getHistory);
    }

    @OpenApi(
            path = "/api/history",
            methods = HttpMethod.GET,
            summary = "プレイヤーの行動履歴を取得する",
            queryParams = {
                    @OpenApiParam(name = "player", type = String.class),
                    @OpenApiParam(name = "page", type = Integer.class)
            },
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = HistoryRepository.HistoryEntry[].class)))
    public void getHistory(Context ctx) throws Exception {
        String player = ctx.queryParam("player");
        int page = PagingParams.page(ctx);
        if (player == null || player.isBlank()) {
            ctx.json(List.<HistoryRepository.HistoryEntry>of());
            return;
        }
        ctx.json(history.queryByPlayer(player, 100, page * 100));
    }
}
