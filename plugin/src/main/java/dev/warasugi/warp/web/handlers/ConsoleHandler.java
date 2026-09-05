package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.config.ConfigProvider;
import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.web.ClientIpResolver;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiRequired;
import io.javalin.openapi.OpenApiResponse;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.Map;

public class ConsoleHandler implements RouteRegistrar {
    private final Plugin plugin;
    private final ConfigProvider config;
    private final AuditRepository audit;

    public ConsoleHandler(Plugin plugin, ConfigProvider config, AuditRepository audit) {
        this.plugin = plugin;
        this.config = config;
        this.audit = audit;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/console", this::execute);
    }

    @OpenApi(
            path = "/api/console",
            methods = HttpMethod.POST,
            summary = "コンソールコマンドを実行する",
            requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ConsoleRequest.class)),
            responses = @OpenApiResponse(status = "204"))
    public void execute(Context ctx) throws Exception {
        var body = ctx.bodyAsClass(ConsoleRequest.class);
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
        boolean blocked = config.get().getCommandBlocklist().stream()
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

    public record ConsoleRequest(@OpenApiRequired String command) {}
}
