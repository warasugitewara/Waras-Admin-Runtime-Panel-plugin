package dev.warasugi.warp;

import dev.warasugi.warp.auth.JwtManager;
import dev.warasugi.warp.auth.RateLimiter;
import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.command.WarpCommand;
import dev.warasugi.warp.config.PanelConfig;
import dev.warasugi.warp.console.WebSocketAppender;
import dev.warasugi.warp.db.*;
import dev.warasugi.warp.listener.ChatListener;
import dev.warasugi.warp.listener.PlayerHistoryListener;
import dev.warasugi.warp.metrics.MetricsCollector;
import dev.warasugi.warp.web.WebServer;
import dev.warasugi.warp.web.handlers.*;
import dev.warasugi.warp.web.middleware.AuthMiddleware;
import dev.warasugi.warp.web.middleware.CsrfMiddleware;
import dev.warasugi.warp.ws.AdminWsHandler;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;
import java.sql.SQLException;

public class WarpPlugin extends JavaPlugin {
    private WebServer webServer;
    private DatabaseManager dbManager;
    private MetricsCollector metricsCollector;

    @Override
    public void onEnable() {
        try {
            // Config
            saveDefaultConfig();
            PanelConfig config = new PanelConfig(getConfig());

            // DB
            Path dataFolder = getDataFolder().toPath();
            dataFolder.toFile().mkdirs();
            dbManager = new DatabaseManager(dataFolder.resolve("warp.db").toString());
            var conn = dbManager.getConnection();
            var logRepo = new LogRepository(conn);
            var chatRepo = new ChatRepository(conn);
            var historyRepo = new HistoryRepository(conn);
            var auditRepo = new AuditRepository(conn);

            // Auth
            String totpSecret = config.getTotpSecret();
            TotpManager totpManager = totpSecret != null && !totpSecret.isBlank()
                    ? new TotpManager(totpSecret) : null;

            JwtManager jwtManager = JwtManager.fromFile(
                    dataFolder.resolve("secret.key"),
                    (long) config.getSessionHours() * 3600_000L
            );

            RateLimiter rateLimiter = new RateLimiter(config.getLoginMaxAttempts(), config.getLoginLockoutMs());

            // Metrics
            metricsCollector = new MetricsCollector();
            metricsCollector.start(this);

            // WS
            AdminWsHandler wsHandler = new AdminWsHandler(jwtManager);
            metricsCollector.addListener(snap -> wsHandler.broadcast("metrics", metricsCollector.getLatestSnapshotAsMap()));
            WebSocketAppender.register(wsHandler, logRepo);

            // Handlers
            AuthHandler authHandler = new AuthHandler(totpManager, jwtManager, rateLimiter, config);
            StatusHandler statusHandler = new StatusHandler(metricsCollector);
            PlayerHandler playerHandler = new PlayerHandler(this);
            BanHandler banHandler = new BanHandler(this, auditRepo);
            ChatHandler chatHandler = new ChatHandler(this, chatRepo, auditRepo);
            ConsoleHandler consoleHandler = new ConsoleHandler(this, config, auditRepo);
            LogHandler logHandler = new LogHandler(logRepo);
            HistoryHandler historyHandler = new HistoryHandler(historyRepo);

            // Middleware
            AuthMiddleware authMw = new AuthMiddleware(jwtManager);
            CsrfMiddleware csrfMw = new CsrfMiddleware();

            // Server
            webServer = new WebServer(this, getFile(), config,
                    authHandler, statusHandler, playerHandler,
                    banHandler, chatHandler, consoleHandler,
                    logHandler, historyHandler, wsHandler, authMw, csrfMw);
            webServer.start(config.getHost(), config.getPort());

            // Listeners
            getServer().getPluginManager().registerEvents(new PlayerHistoryListener(historyRepo), this);
            getServer().getPluginManager().registerEvents(new ChatListener(this, chatRepo), this);

            // Command
            WarpCommand warpCommand = new WarpCommand(this, authHandler, metricsCollector);
            var cmd = getCommand("warp");
            if (cmd != null) {
                cmd.setExecutor(warpCommand);
                cmd.setTabCompleter(warpCommand);
            }

            getLogger().info("WARP 起動完了 — port=" + config.getPort());
            if (totpManager == null) {
                getLogger().warning("TOTP未設定。/warp setup を実行してください。");
            }

        } catch (Exception e) {
            getLogger().severe("WARP 起動失敗: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        if (metricsCollector != null) metricsCollector.stop();
        if (dbManager != null) {
            try { dbManager.close(); } catch (SQLException e) { /* ignore */ }
        }
        getLogger().info("WARP 停止完了");
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }
}
