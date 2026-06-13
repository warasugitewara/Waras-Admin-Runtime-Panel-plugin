package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class PlayerHandler implements RouteRegistrar {
    private final Plugin plugin;

    public PlayerHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    public record PlayerDto(String name, String uuid, int ping, String world, double x, double y, double z) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/players", this::getPlayers);
    }

    public void getPlayers(Context ctx) throws ExecutionException, InterruptedException {
        List<PlayerDto> list = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<PlayerDto> players = new ArrayList<>();
            for (var p : Bukkit.getOnlinePlayers()) {
                var loc = p.getLocation();
                players.add(new PlayerDto(p.getName(), p.getUniqueId().toString(), p.getPing(),
                        p.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
            }
            return players;
        }).get();
        ctx.json(list);
    }
}
