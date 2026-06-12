package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.HistoryRepository;
import dev.warasugi.warp.web.PagingParams;
import io.javalin.http.Context;

public class HistoryHandler {
    private final HistoryRepository history;

    public HistoryHandler(HistoryRepository history) {
        this.history = history;
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
