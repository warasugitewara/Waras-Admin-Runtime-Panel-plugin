package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequired;
import io.javalin.openapi.OpenApiResponse;
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

    public record PlayerDto(@OpenApiRequired String name, @OpenApiRequired String uuid, int ping,
                             @OpenApiRequired String world, double x, double y, double z) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/players", this::getPlayers);
    }

    @OpenApi(
            path = "/api/players",
            methods = HttpMethod.GET,
            summary = "オンラインプレイヤー一覧を取得する",
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = PlayerDto[].class)))
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
