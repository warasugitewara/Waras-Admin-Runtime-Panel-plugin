package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.config.PanelConfig;
import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.web.ClientIpResolver;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.Map;

public class ConsoleHandler implements RouteRegistrar {
    private final Plugin plugin;
    private final PanelConfig config;
    private final AuditRepository audit;

    public ConsoleHandler(Plugin plugin, PanelConfig config, AuditRepository audit) {
        this.plugin = plugin;
        this.config = config;
        this.audit = audit;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/console", this::execute);
    }

    public void execute(Context ctx) throws Exception {
        record Body(String command) {}
        var body = ctx.bodyAsClass(Body.class);
        if (body.command() == null || body.command().isBlank()) {
            throw new HttpResponseException(400, "command must not be empty");
        }
        String cmd = body.command().strip();
        String rawCmdBase = cmd.split(" ")[0].toLowerCase();
        // 先頭の "/" は Bukkit.dispatchCommand 側で無視されるため、比較前に取り除く
        if (rawCmdBase.startsWith("/")) {
            rawCmdBase = rawCmdBase.substring(1);
        }
        // "minecraft:stop" のような名前空間付き表記も比較できるよう ":" 以降を取り出す
        int colonIdx = rawCmdBase.indexOf(':');
        String cmdBase = colonIdx >= 0 ? rawCmdBase.substring(colonIdx + 1) : rawCmdBase;
        boolean blocked = config.getCommandBlocklist().stream()
                .anyMatch(b -> b.toLowerCase().equals(cmdBase));
        if (blocked) {
            throw new HttpResponseException(403, "Command blocked");
        }
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            return null;
        }).get();
        audit.record(ClientIpResolver.resolve(ctx), "console", Map.of("cmd", cmd));
        ctx.status(204);
    }
}
