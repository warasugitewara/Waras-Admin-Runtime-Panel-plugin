package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import io.javalin.http.Context;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@SuppressWarnings("deprecation")
public class BanHandler {
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
        record Body(String player, String reason) {}
        var body = ctx.bodyAsClass(Body.class);
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.NAME).addBan(body.player(), body.reason(), null, "WARP");
            return null;
        }).get();
        audit.insert(System.currentTimeMillis(), ctx.ip(), "ban", "{\"player\":\"" + body.player() + "\"}");
        ctx.status(201);
    }

    public void removeBan(Context ctx) throws Exception {
        String player = ctx.pathParam("player");
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.NAME).pardon(player);
            return null;
        }).get();
        audit.insert(System.currentTimeMillis(), ctx.ip(), "unban", "{\"player\":\"" + player + "\"}");
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
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.IP).addBan(body.ip(), body.reason(), null, "WARP");
            return null;
        }).get();
        audit.insert(System.currentTimeMillis(), ctx.ip(), "ipban", "{\"ip\":\"" + body.ip() + "\"}");
        ctx.status(201);
    }

    public void removeIpBan(Context ctx) throws Exception {
        String ip = ctx.pathParam("ip");
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.getBanList(BanList.Type.IP).pardon(ip);
            return null;
        }).get();
        audit.insert(System.currentTimeMillis(), ctx.ip(), "unipban", "{\"ip\":\"" + ip + "\"}");
        ctx.status(204);
    }
}
