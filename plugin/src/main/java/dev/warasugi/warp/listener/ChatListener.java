package dev.warasugi.warp.listener;

import dev.warasugi.warp.db.ChatRepository;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {
    private final Plugin plugin;
    private final ChatRepository chat;

    public ChatListener(Plugin plugin, ChatRepository chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        long ts = System.currentTimeMillis();
        String uuid = e.getPlayer().getUniqueId().toString();
        String name = e.getPlayer().getName();
        String message = PlainTextComponentSerializer.plainText().serialize(e.message());
        // AsyncChatEvent は非同期スレッドで発火するため、共有DB接続への書き込みはメインスレッドに集約する
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                chat.insert(ts, uuid, name, message);
            } catch (Exception ex) {
                // ignore db errors in event path
            }
        });
    }
}
