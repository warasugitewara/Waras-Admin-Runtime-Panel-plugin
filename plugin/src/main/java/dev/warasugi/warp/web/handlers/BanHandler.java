package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.web.ClientIpResolver;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public class BanHandler {
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

    public void getBans(Context ctx) throws ExecutionException, InterruptedException {
        List<Map<String, Object>> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var entry : Bukkit.getBanList(BanList.Type.NAME).getEntries()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("player", entry.getTarget());
                m.put("reason", entry.getReason());
                m.put("expires", entry.getExpiration() != null ? entry.getExpiration().toInstant().toEpochMilli() : null);
                result.add(m);
            }
            return result;
        }).get();
        ctx.json(list);
    }

    public void addBan(Context ctx) throws Exception {
        record Body(String player, String reason, Long duration) {}
        var body = ctx.bodyAsClass(Body.class);
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

    public void removeBan(Context ctx) throws Exception {
        String player = ctx.pathParam("player");
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.NAME).pardon(player);
            return null;
        }).get();
        audit.record(ClientIpResolver.resolve(ctx), "unban", Map.of("player", player));
        ctx.status(204);
    }

    public void getIpBans(Context ctx) throws ExecutionException, InterruptedException {
        List<Map<String, Object>> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var entry : Bukkit.getBanList(BanList.Type.IP).getEntries()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ip", entry.getTarget());
                m.put("reason", entry.getReason());
                m.put("expires", entry.getExpiration() != null ? entry.getExpiration().toInstant().toEpochMilli() : null);
                result.add(m);
            }
            return result;
        }).get();
        ctx.json(list);
    }

    public void addIpBan(Context ctx) throws Exception {
        record Body(String ip, String reason) {}
        var body = ctx.bodyAsClass(Body.class);
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
