package dev.warasugi.warp.web.handlers;

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
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public class BanHandler implements RouteRegistrar {
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^\\w{3,16}$");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    // Instant.plusSeconds のオーバーフローを避けるための duration 上限 (100年)
    private static final long MAX_DURATION_SECONDS = 100L * 365 * 24 * 3600;

    private final Plugin plugin;
    private final AuditRepository audit;

    public BanHandler(Plugin plugin, AuditRepository audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    public record BanDto(@OpenApiRequired String player, @OpenApiRequired @OpenApiNullable String reason, @OpenApiRequired @OpenApiNullable Long expires) {}
    public record IpBanDto(@OpenApiRequired String ip, @OpenApiRequired @OpenApiNullable String reason, @OpenApiRequired @OpenApiNullable Long expires) {}
    public record BanRequest(@OpenApiRequired String player, @OpenApiRequired String reason, Long duration) {}
    public record IpBanRequest(@OpenApiRequired String ip, @OpenApiRequired String reason) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/bans", this::getBans);
        app.post("/api/bans", this::addBan);
        app.delete("/api/bans/{player}", this::removeBan);
        app.get("/api/ipbans", this::getIpBans);
        app.post("/api/ipbans", this::addIpBan);
        app.delete("/api/ipbans/{ip}", this::removeIpBan);
    }

    @OpenApi(
            path = "/api/bans",
            methods = HttpMethod.GET,
            summary = "BAN一覧を取得する",
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = BanDto[].class)))
    public void getBans(Context ctx) throws ExecutionException, InterruptedException {
        List<BanDto> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<BanDto> result = new ArrayList<>();
            for (var entry : Bukkit.getBanList(BanList.Type.NAME).getEntries()) {
                Long expires = entry.getExpiration() != null ? entry.getExpiration().toInstant().toEpochMilli() : null;
                result.add(new BanDto(entry.getTarget(), entry.getReason(), expires));
            }
            return result;
        }).get();
        ctx.json(list);
    }

    @OpenApi(
            path = "/api/bans",
            methods = HttpMethod.POST,
            summary = "プレイヤーをBANする",
            requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = BanRequest.class)),
            responses = @OpenApiResponse(status = "201"))
    public void addBan(Context ctx) throws Exception {
        var body = ctx.bodyAsClass(BanRequest.class);
        if (body.player() == null || !PLAYER_NAME_PATTERN.matcher(body.player()).matches()) {
            throw new HttpResponseException(400, "player must be a valid Minecraft username (3-16 chars)");
        }
        if (body.duration() != null && body.duration() > MAX_DURATION_SECONDS) {
            throw new HttpResponseException(400, "duration too large");
        }
        Date expires = (body.duration() != null && body.duration() > 0)
                ? Date.from(Instant.now().plusSeconds(body.duration()))
                : null;
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.NAME).addBan(body.player(), body.reason(), expires, "WARP");
            return null;
        }).get();
        audit.record(ClientIpResolver.resolve(ctx), "ban",
                Map.of("player", body.player(), "reason", body.reason() == null ? "" : body.reason()));
        ctx.status(201);
    }

    @OpenApi(
            path = "/api/bans/{player}",
            methods = HttpMethod.DELETE,
            summary = "プレイヤーのBANを解除する",
            pathParams = @OpenApiParam(name = "player", type = String.class, required = true),
            responses = @OpenApiResponse(status = "204"))
    public void removeBan(Context ctx) throws Exception {
        String player = ctx.pathParam("player");
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.NAME).pardon(player);
            return null;
        }).get();
        audit.record(ClientIpResolver.resolve(ctx), "unban", Map.of("player", player));
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/ipbans",
            methods = HttpMethod.GET,
            summary = "IPBAN一覧を取得する",
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = IpBanDto[].class)))
    public void getIpBans(Context ctx) throws ExecutionException, InterruptedException {
        List<IpBanDto> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<IpBanDto> result = new ArrayList<>();
            for (var entry : Bukkit.getBanList(BanList.Type.IP).getEntries()) {
                Long expires = entry.getExpiration() != null ? entry.getExpiration().toInstant().toEpochMilli() : null;
                result.add(new IpBanDto(entry.getTarget(), entry.getReason(), expires));
            }
            return result;
        }).get();
        ctx.json(list);
    }

    @OpenApi(
            path = "/api/ipbans",
            methods = HttpMethod.POST,
            summary = "IPアドレスをBANする",
            requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = IpBanRequest.class)),
            responses = @OpenApiResponse(status = "201"))
    public void addIpBan(Context ctx) throws Exception {
        var body = ctx.bodyAsClass(IpBanRequest.class);
        if (body.ip() == null || !IPV4_PATTERN.matcher(body.ip()).matches()) {
            throw new HttpResponseException(400, "ip must be a valid IPv4 address");
        }
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.IP).addBan(body.ip(), body.reason(), null, "WARP");
            return null;
        }).get();
        audit.record(ClientIpResolver.resolve(ctx), "ipban",
                Map.of("ip", body.ip(), "reason", body.reason() == null ? "" : body.reason()));
        ctx.status(201);
    }

    @OpenApi(
            path = "/api/ipbans/{ip}",
            methods = HttpMethod.DELETE,
            summary = "IPアドレスのBANを解除する",
            pathParams = @OpenApiParam(name = "ip", type = String.class, required = true),
            responses = @OpenApiResponse(status = "204"))
    public void removeIpBan(Context ctx) throws Exception {
        String ip = ctx.pathParam("ip");
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.IP).pardon(ip);
            return null;
        }).get();
        audit.record(ClientIpResolver.resolve(ctx), "unipban", Map.of("ip", ip));
        ctx.status(204);
    }
}
