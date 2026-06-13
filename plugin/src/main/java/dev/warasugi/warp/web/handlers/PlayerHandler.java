package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class PlayerHandler implements RouteRegistrar {
    private final Plugin plugin;

    public PlayerHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/players", this::getPlayers);
    }

    public void getPlayers(Context ctx) throws ExecutionException, InterruptedException {
        List<Map<String, Object>> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<Map<String, Object>> players = new ArrayList<>();
            for (var p : Bukkit.getOnlinePlayers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getName());
                m.put("uuid", p.getUniqueId().toString());
                m.put("ping", p.getPing());
                m.put("world", p.getWorld().getName());
                m.put("x", p.getLocation().getX());
                m.put("y", p.getLocation().getY());
                m.put("z", p.getLocation().getZ());
                players.add(m);
            }
            return players;
        }).get();
        ctx.json(list);
    }
}
