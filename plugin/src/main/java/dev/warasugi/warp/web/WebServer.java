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
import io.javalin.http.staticfiles.Location;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class WebServer {

    private final Javalin app;
    private final Plugin plugin;

    public WebServer(Plugin plugin, File pluginJar, PanelConfig config,
                     AuthHandler auth, StatusHandler status, PlayerHandler player,
                     BanHandler ban, ChatHandler chat, ConsoleHandler console,
                     LogHandler log, HistoryHandler history,
                     AdminWsHandler ws, AuthMiddleware authMw, CsrfMiddleware csrfMw) throws Exception {
        this.plugin = plugin;

        // クラスローダーに依存せず物理 JAR から直接展開する（PluginRemapper 対策）
        Path webRoot = extractWebResources(plugin, pluginJar);

        app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;
            cfg.staticFiles.add(webRoot.toAbsolutePath().toString(), Location.EXTERNAL);
            cfg.spaRoot.addFile("/", webRoot.resolve("index.html").toAbsolutePath().toString(), Location.EXTERNAL);

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
    }

    /**
     * pluginJar（plugins/warp-*.jar）の web/ エントリをデータフォルダに展開する。
     * Paper の PluginRemapper はクラスを書き換えるが resources はそのまま。
     * JarFile で物理ファイルを直接読むことでクラスローダーの差異を回避する。
     */
    private static Path extractWebResources(Plugin plugin, File pluginJar) throws Exception {
        Path dir = plugin.getDataFolder().toPath().resolve("web-cache");
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        }
        Files.createDirectories(dir);
        try (var jar = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("web/") && !entry.isDirectory()) {
                    String rel = entry.getName().substring(4); // "web/" を除去
                    Path dest = dir.resolve(rel);
                    Files.createDirectories(dest.getParent());
                    try (var in = jar.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        return dir;
    }

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
