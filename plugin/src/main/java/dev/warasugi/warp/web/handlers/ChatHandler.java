package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.db.ChatRepository;
import dev.warasugi.warp.web.ClientIpResolver;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiRequired;
import io.javalin.openapi.OpenApiResponse;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.Map;

public class ChatHandler implements RouteRegistrar {
    private final Plugin plugin;
    private final ChatRepository chat;
    private final AuditRepository audit;

    public ChatHandler(Plugin plugin, ChatRepository chat, AuditRepository audit) {
        this.plugin = plugin;
        this.chat = chat;
        this.audit = audit;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/chat", this::getChat);
        app.post("/api/chat", this::sendChat);
    }

    @OpenApi(
            path = "/api/chat",
            methods = HttpMethod.GET,
            summary = "チャット履歴を取得する",
            queryParams = @OpenApiParam(name = "page", type = Integer.class),
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = ChatRepository.ChatEntry[].class)))
    public void getChat(Context ctx) throws Exception {
        int page = PagingParams.page(ctx);
        ctx.json(chat.query(null, 100, page * 100));
    }

    @OpenApi(
            path = "/api/chat",
            methods = HttpMethod.POST,
            summary = "チャットメッセージを送信する",
            requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ChatRequest.class)),
            responses = @OpenApiResponse(status = "204"))
    public void sendChat(Context ctx) throws Exception {
        var body = ctx.bodyAsClass(ChatRequest.class);
        if (body.message() == null || body.message().isBlank()) {
            throw new HttpResponseException(400, "message must not be empty");
        }
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.broadcast(Component.text("[WARP] " + body.message()));
            return null;
        }).get();
        chat.insert(System.currentTimeMillis(), "admin", "Admin", body.message());
        audit.record(ClientIpResolver.resolve(ctx), "chat", Map.of("msg", body.message()));
        ctx.status(204);
    }

    public record ChatRequest(@OpenApiRequired String message) {}
}
