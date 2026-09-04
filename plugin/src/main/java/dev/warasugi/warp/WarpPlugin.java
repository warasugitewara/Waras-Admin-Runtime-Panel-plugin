package dev.warasugi.warp;

import dev.warasugi.warp.auth.JwtManager;
import dev.warasugi.warp.auth.RateLimiter;
import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.command.WarpCommand;
import dev.warasugi.warp.config.ConfigProvider;
import dev.warasugi.warp.config.PanelConfig;
import dev.warasugi.warp.console.WebSocketAppender;
import dev.warasugi.warp.db.*;
import dev.warasugi.warp.listener.ChatListener;
import dev.warasugi.warp.listener.PlayerHistoryListener;
import dev.warasugi.warp.metrics.MetricsCollector;
import dev.warasugi.warp.schemdepot.SchemDepotReader;
import dev.warasugi.warp.web.RouteRegistrar;
import dev.warasugi.warp.web.WebServer;
import dev.warasugi.warp.web.handlers.*;
import dev.warasugi.warp.web.middleware.AuthMiddleware;
import dev.warasugi.warp.web.middleware.CsrfMiddleware;
import dev.warasugi.warp.ws.AdminWsHandler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

public class WarpPlugin extends JavaPlugin {
    private static final long PRUNE_INITIAL_DELAY_TICKS = 20L * 60;
    private static final long PRUNE_INTERVAL_TICKS = 20L * 60 * 30;

    private WebServer webServer;
    private DatabaseManager dbManager;
    private MetricsCollector metricsCollector;
    private WebSocketAppender webSocketAppender;
    private BukkitTask pruneTask;
    private ConfigProvider configProvider;

    private record DatabaseContext(
            LogRepository logRepo,
            ChatRepository chatRepo,
            HistoryRepository historyRepo,
            AuditRepository auditRepo) {}

    private record AuthContext(
            TotpManager totpManager,
            JwtManager jwtManager,
            RateLimiter rateLimiter,
            AuthHandler authHandler) {}

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> T stage(String label, ThrowingSupplier<T> supplier) throws Exception {
        try {
            return supplier.get();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, label + "に失敗しました", e);
            throw e;
        }
    }

    @Override
    public void onEnable() {
        try {
            PanelConfig config = stage("設定の読み込み", this::initConfig);
            DatabaseContext db = stage("データベースの初期化", this::initDatabase);
            AuthContext auth = stage("認証の初期化", () -> initAuth(config));
            stage("メトリクス収集の初期化", () -> { initMetrics(); return null; });
            stage("Webサーバーの初期化", () -> { initWeb(config, db, auth); return null; });
            stage("コマンドの登録", () -> { initCommands(auth.authHandler()); return null; });

            getLogger().info("WARP 起動完了 — port=" + config.getPort());
            if (auth.totpManager() == null) {
                getLogger().warning("TOTP未設定。/warp setup を実行してください。");
            }
        } catch (Exception e) {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private PanelConfig initConfig() {
        saveDefaultConfig();
        PanelConfig config = new PanelConfig(getConfig());
        configProvider = new ConfigProvider(config);
        return config;
    }

    private DatabaseContext initDatabase() throws SQLException {
        Path dataFolder = getDataFolder().toPath();
        dataFolder.toFile().mkdirs();
        dbManager = new DatabaseManager(dataFolder.resolve("warp.db").toString());
        var conn = dbManager.getConnection();
        return new DatabaseContext(
                new LogRepository(conn),
                new ChatRepository(conn),
                new HistoryRepository(conn),
                new AuditRepository(conn));
    }

    private AuthContext initAuth(PanelConfig config) throws IOException {
        String totpSecret = config.getTotpSecret();
        TotpManager totpManager = totpSecret != null && !totpSecret.isBlank()
                ? new TotpManager(totpSecret) : null;

        JwtManager jwtManager = JwtManager.fromFile(
                getDataFolder().toPath().resolve("secret.key"),
                (long) config.getSessionHours() * 3600_000L
        );

        RateLimiter rateLimiter = new RateLimiter(config.getLoginMaxAttempts(), config.getLoginLockoutMs());
        AuthHandler authHandler = new AuthHandler(totpManager, jwtManager, rateLimiter, configProvider);
        return new AuthContext(totpManager, jwtManager, rateLimiter, authHandler);
    }

    private void initMetrics() {
        metricsCollector = new MetricsCollector();
        metricsCollector.start(this);
    }

    private void initWeb(PanelConfig config, DatabaseContext db, AuthContext auth) throws Exception {
        AdminWsHandler wsHandler = new AdminWsHandler(auth.jwtManager());
        metricsCollector.addListener(snap -> wsHandler.broadcast("metrics", metricsCollector.toMap(snap)));
        webSocketAppender = WebSocketAppender.register(wsHandler, db.logRepo());

        StatusHandler statusHandler = new StatusHandler(metricsCollector);
        PlayerHandler playerHandler = new PlayerHandler(this);
        BanHandler banHandler = new BanHandler(this, db.auditRepo());
        ChatHandler chatHandler = new ChatHandler(this, db.chatRepo(), db.auditRepo());
        ConsoleHandler consoleHandler = new ConsoleHandler(this, configProvider, db.auditRepo());
        LogHandler logHandler = new LogHandler(db.logRepo());
        HistoryHandler historyHandler = new HistoryHandler(db.historyRepo());
        AuditHandler auditHandler = new AuditHandler(db.auditRepo());
        PluginHandler pluginHandler = new PluginHandler(this, db.auditRepo());

        // SchemDepot はオプション扱い。plugins/ 配下を読むだけで、依存も参照も持たない。
        // 未導入なら status が available=false を返し、フロント側で項目ごと隠れる。
        SchemDepotReader schemDepotReader =
                new SchemDepotReader(getDataFolder().toPath().getParent(), getLogger());
        SchemDepotHandler schemDepotHandler = new SchemDepotHandler(schemDepotReader);

        AuthMiddleware authMw = new AuthMiddleware(auth.jwtManager());
        CsrfMiddleware csrfMw = new CsrfMiddleware();

        List<RouteRegistrar> handlers = List.of(
                auth.authHandler(), statusHandler, playerHandler, banHandler,
                chatHandler, consoleHandler, logHandler, historyHandler, auditHandler,
                pluginHandler, schemDepotHandler, wsHandler);

        webServer = new WebServer(this, getFile(), config, authMw, csrfMw, handlers);
        webServer.start(config.getHost(), config.getPort());

        getServer().getPluginManager().registerEvents(new PlayerHistoryListener(db.historyRepo()), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this, db.chatRepo()), this);

        // 保持上限 (storage.*-max-rows) を超えたログ/チャット/履歴を定期的にプルーニング
        // audit は長期保持のため意図的にプルーニング対象外
        pruneTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                db.logRepo().pruneToMax(configProvider.get().getLogsMaxRows());
                db.chatRepo().pruneToMax(configProvider.get().getChatMaxRows());
                db.historyRepo().pruneToMax(configProvider.get().getHistoryMaxRows());
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "保持上限のプルーニングに失敗しました", e);
            }
        }, PRUNE_INITIAL_DELAY_TICKS, PRUNE_INTERVAL_TICKS);
    }

    private void initCommands(AuthHandler authHandler) {
        WarpCommand warpCommand = new WarpCommand(this, authHandler, metricsCollector, configProvider);
        var cmd = getCommand("warp");
        if (cmd != null) {
            cmd.setExecutor(warpCommand);
            cmd.setTabCompleter(warpCommand);
        }
    }

    @Override
    public void onDisable() {
        if (pruneTask != null) pruneTask.cancel();
        if (webServer != null) webServer.stop();
        if (metricsCollector != null) metricsCollector.stop();
        if (webSocketAppender != null) WebSocketAppender.unregister(webSocketAppender);
        if (dbManager != null) {
            try { dbManager.close(); } catch (SQLException e) { getLogger().log(Level.WARNING, "DB接続のクローズに失敗しました", e); }
        }
        getLogger().info("WARP 停止完了");
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }
}
