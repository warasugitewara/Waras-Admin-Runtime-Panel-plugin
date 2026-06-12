package dev.warasugi.warp.listener;

import dev.warasugi.warp.db.HistoryRepository;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerHistoryListener implements Listener {
    private final HistoryRepository history;

    public PlayerHistoryListener(HistoryRepository history) {
        this.history = history;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        record(e.getPlayer(), "join");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        record(e.getPlayer(), "quit");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        record(e.getEntity(), "death");
    }

    private void record(Player player, String eventType) {
        Location loc = player.getLocation();
        try {
            history.insert(System.currentTimeMillis(), player.getUniqueId().toString(), player.getName(),
                    eventType, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        } catch (Exception ex) {
            // ignore db errors in event path
        }
    }
}
