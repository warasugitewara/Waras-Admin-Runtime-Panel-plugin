package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.db.ChatRepository;
import io.javalin.http.Context;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

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
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Bukkit.broadcast(Component.text("[WARP] " + body.message()));
            return null;
        }).get();
        audit.insert(System.currentTimeMillis(), ctx.ip(), "chat", "{\"msg\":\"" + body.message() + "\"}");
        ctx.status(204);
    }

    private int parseInt(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }
}
