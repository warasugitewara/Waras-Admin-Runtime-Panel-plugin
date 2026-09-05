package dev.warasugi.warp.web.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.web.ClientIpResolver;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiNullable;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequired;
import io.javalin.openapi.OpenApiResponse;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

@SuppressWarnings("deprecation")
public class PluginHandler implements RouteRegistrar {
    private static final String RELEASES_API =
            "https://api.github.com/repos/warasugitewara/Waras-Admin-Runtime-Panel-plugin/releases/latest";
    // GitHub API のレート制限を避けるため、結果を一定時間キャッシュする
    private static final long UPDATE_CHECK_CACHE_MS = 30 * 60_000L;

    private final Plugin plugin;
    private final AuditRepository audit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile SelfUpdateInfo cachedUpdateInfo;
    private volatile long cachedAt = 0L;

    public record PluginDto(
            @OpenApiRequired String name, @OpenApiRequired String version, boolean enabled,
            @OpenApiRequired @OpenApiNullable String description, @OpenApiRequired List<String> authors, boolean self) {}

    public record SelfUpdateInfo(
            @OpenApiRequired String currentVersion, @OpenApiRequired @OpenApiNullable String latestVersion,
            boolean updateAvailable, @OpenApiRequired @OpenApiNullable String releaseUrl) {}

    public PluginHandler(Plugin plugin, AuditRepository audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/plugins", this::getPlugins);
        app.post("/api/plugins/{name}/enable", this::enablePlugin);
        app.post("/api/plugins/{name}/disable", this::disablePlugin);
        app.get("/api/plugins/self-update", this::getSelfUpdate);
    }

    @OpenApi(
            path = "/api/plugins",
            methods = HttpMethod.GET,
            summary = "プラグイン一覧を取得する",
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = PluginDto[].class)))
    public void getPlugins(Context ctx) throws ExecutionException, InterruptedException {
        List<PluginDto> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<PluginDto> result = new ArrayList<>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                PluginDescriptionFile desc = p.getDescription();
                result.add(new PluginDto(
                        p.getName(), desc.getVersion(), p.isEnabled(),
                        desc.getDescription(), desc.getAuthors(),
                        p.getName().equalsIgnoreCase(plugin.getName())));
            }
            return result;
        }).get();
        ctx.json(list);
    }

    @OpenApi(
            path = "/api/plugins/{name}/enable",
            methods = HttpMethod.POST,
            summary = "プラグインを有効化する",
            pathParams = @OpenApiParam(name = "name", type = String.class, required = true),
            responses = @OpenApiResponse(status = "204"))
    public void enablePlugin(Context ctx) throws Exception {
        setEnabled(ctx, true);
    }

    @OpenApi(
            path = "/api/plugins/{name}/disable",
            methods = HttpMethod.POST,
            summary = "プラグインを無効化する",
            pathParams = @OpenApiParam(name = "name", type = String.class, required = true),
            responses = @OpenApiResponse(status = "204"))
    public void disablePlugin(Context ctx) throws Exception {
        setEnabled(ctx, false);
    }

    public void setEnabled(Context ctx, boolean enabled) throws Exception {
        String name = ctx.pathParam("name");
        if (name.equalsIgnoreCase(plugin.getName())) {
            throw new HttpResponseException(400, "WARP自身は無効化できません");
        }
        boolean found = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target == null) return false;
            if (enabled) {
                Bukkit.getPluginManager().enablePlugin(target);
            } else {
                Bukkit.getPluginManager().disablePlugin(target);
            }
            return true;
        }).get();
        if (!found) {
            throw new HttpResponseException(404, "Plugin not found: " + name);
        }
        audit.record(ClientIpResolver.resolve(ctx), enabled ? "plugin-enable" : "plugin-disable",
                Map.of("plugin", name));
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/plugins/self-update",
            methods = HttpMethod.GET,
            summary = "WARP自身の更新情報を取得する",
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = SelfUpdateInfo.class)))
    public void getSelfUpdate(Context ctx) {
        ctx.json(checkSelfUpdate());
    }

    @SuppressWarnings("unchecked")
    private SelfUpdateInfo checkSelfUpdate() {
        long now = System.currentTimeMillis();
        SelfUpdateInfo cached = cachedUpdateInfo;
        if (cached != null && now - cachedAt < UPDATE_CHECK_CACHE_MS) {
            return cached;
        }
        String current = plugin.getDescription().getVersion();
        SelfUpdateInfo result;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_API))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("GitHub API returned " + res.statusCode());
            }
            Map<String, Object> json = mapper.readValue(res.body(), Map.class);
            String tag = String.valueOf(json.get("tag_name"));
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            String url = String.valueOf(json.get("html_url"));
            result = new SelfUpdateInfo(current, latest, !latest.equals(current), url);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "更新チェックに失敗しました", e);
            result = new SelfUpdateInfo(current, null, false, null);
        }
        cachedUpdateInfo = result;
        cachedAt = now;
        return result;
    }
}
