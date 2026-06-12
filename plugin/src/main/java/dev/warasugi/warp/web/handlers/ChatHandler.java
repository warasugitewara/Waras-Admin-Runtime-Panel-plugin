package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.db.ChatRepository;
import dev.warasugi.warp.web.ClientIpResolver;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.Map;

public class ChatHandler {
    private final Plugin plugin;
    private final ChatRepository chat;
    private final AuditRepository audit;

    public ChatHandler(Plugin plugin, ChatRepository chat, AuditRepository audit) {
        this.plugin = plugin;
        this.chat = chat;
        this.audit = audit;
    }

    public void getChat(Context ctx) throws Exception {
        int page = parseInt(ctx.queryParam("page"), 0);
        ctx.json(chat.query(null, 100, page * 100));
    }

    public void sendChat(Context ctx) throws Exception {
        record Body(String message) {}
        var body = ctx.bodyAsClass(Body.class);
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

    private int parseInt(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }
}
