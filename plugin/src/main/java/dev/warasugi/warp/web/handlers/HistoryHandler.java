package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.HistoryRepository;
import io.javalin.http.Context;

public class HistoryHandler {
    private final HistoryRepository history;

    public HistoryHandler(HistoryRepository history) {
        this.history = history;
    }

    public void getHistory(Context ctx) throws Exception {
        String player = ctx.queryParam("player");
        int page = parseInt(ctx.queryParam("page"), 0);
        if (player == null || player.isBlank()) {
            ctx.json(new Object[0]);
            return;
        }
        ctx.json(history.queryByPlayer(player, 100, page * 100));
    }

    private int parseInt(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }
}
