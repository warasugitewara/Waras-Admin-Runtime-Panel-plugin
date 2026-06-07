package dev.warasugi.warp.web;

import dev.warasugi.warp.config.PanelConfig;
import dev.warasugi.warp.web.handlers.AuthHandler;
import dev.warasugi.warp.web.handlers.BanHandler;
import dev.warasugi.warp.web.handlers.ChatHandler;
import dev.warasugi.warp.web.handlers.ConsoleHandler;
import dev.warasugi.warp.web.handlers.HistoryHandler;
import dev.warasugi.warp.web.handlers.LogHandler;
import dev.warasugi.warp.web.handlers.PlayerHandler;
import dev.warasugi.warp.web.handlers.StatusHandler;
import dev.warasugi.warp.web.middleware.AuthMiddleware;
import dev.warasugi.warp.web.middleware.CsrfMiddleware;
import dev.warasugi.warp.ws.AdminWsHandler;
import io.javalin.Javalin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.Callable;

public class WebServer {

    private final Javalin app;
    private final Plugin plugin;

    public WebServer(Plugin plugin, PanelConfig config,
                     AuthHandler auth, StatusHandler status, PlayerHandler player,
                     BanHandler ban, ChatHandler chat, ConsoleHandler console,
                     LogHandler log, HistoryHandler history,
                     AdminWsHandler ws, AuthMiddleware authMw, CsrfMiddleware csrfMw) {
        this.plugin = plugin;

        app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;

            List<String> origins = config.getCorsOrigins();
            if (!origins.isEmpty()) {
                cfg.bundledPlugins.enableCors(cors -> {
                    cors.addRule(rule -> {
                        String first = origins.get(0);
                        String[] rest = origins.subList(1, origins.size()).toArray(new String[0]);
                        rule.allowHost(first, rest);
                        rule.allowCredentials = true;
                    });
                });
            }
        });

        // Middleware
        app.before(authMw::handle);
        app.before(csrfMw::handle);

        // Auth
        app.post("/api/auth/login", auth::login);
        app.post("/api/auth/logout", auth::logout);

        // Status
        app.get("/api/status", status::get);

        // Players
        app.get("/api/players", player::getPlayers);

        // Bans
        app.get("/api/bans", ban::getBans);
        app.post("/api/bans", ban::addBan);
        app.delete("/api/bans/{player}", ban::removeBan);
        app.get("/api/ipbans", ban::getIpBans);
        app.post("/api/ipbans", ban::addIpBan);
        app.delete("/api/ipbans/{ip}", ban::removeIpBan);

        // Chat
        app.get("/api/chat", chat::getChat);
        app.post("/api/chat", chat::sendChat);

        // Console
        app.post("/api/console", console::execute);

        // Logs
        app.get("/api/logs", log::getLogs);

        // History
        app.get("/api/history", history::getHistory);

        // WebSocket
        app.ws("/ws", wsConfig -> {
            wsConfig.onConnect(ws::onConnect);
            wsConfig.onClose(ws::onClose);
            wsConfig.onError(ws::onError);
        });

        // Static: SPA index
        app.get("/", ctx -> {
            ctx.contentType("text/html");
            var stream = getClass().getResourceAsStream("/web/index.html");
            if (stream != null) {
                ctx.result(stream);
            } else {
                ctx.result("WARP Panel - Frontend not bundled");
            }
        });

        // SPA フォールバック (404 で /api・/ws 以外はindex.htmlを返す)
        app.error(404, ctx -> {
            String path = ctx.path();
            if (!path.startsWith("/api") && !path.startsWith("/ws")) {
                ctx.contentType("text/html");
                var stream = getClass().getResourceAsStream("/web/index.html");
                if (stream != null) {
                    ctx.result(stream);
                }
            }
        });
    }

    /**
     * Bukkit メインスレッドで callable を実行して結果を返す。
     * すでにメインスレッド上であればそのまま call() する。
     */
    public <T> T sync(Callable<T> callable) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return callable.call();
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, callable).get();
    }

    public void start(String host, int port) {
        app.start(host, port);
    }

    public void stop() {
        app.stop();
    }
}
