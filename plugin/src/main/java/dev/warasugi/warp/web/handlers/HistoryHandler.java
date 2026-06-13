package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.HistoryRepository;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;

public class HistoryHandler implements RouteRegistrar {
    private final HistoryRepository history;

    public HistoryHandler(HistoryRepository history) {
        this.history = history;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/history", this::getHistory);
    }

    public void getHistory(Context ctx) throws Exception {
        String player = ctx.queryParam("player");
        int page = PagingParams.page(ctx);
        if (player == null || player.isBlank()) {
            ctx.json(new Object[0]);
            return;
        }
        ctx.json(history.queryByPlayer(player, 100, page * 100));
    }
}
