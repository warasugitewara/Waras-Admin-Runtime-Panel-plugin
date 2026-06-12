package dev.warasugi.warp.metrics;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class MetricsCollector {
    public record Snapshot(double tps1, double tps5, double tps15, double mspt,
                           int players, long memoryUsedMb) {}

    private volatile Snapshot latest;
    private BukkitTask task;
    private final long startMs = System.currentTimeMillis();
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    public void start(Plugin plugin) {
        latest = buildSnapshot();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            latest = buildSnapshot();
            Snapshot snapshot = latest;
            // リスナー（WS broadcast 等）はメインスレッドを塞がないよう非同期で呼び出す
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                for (var listener : listeners) {
                    listener.accept(snapshot);
                }
            });
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public void addListener(Consumer<Snapshot> listener) {
        listeners.add(listener);
    }

    public Snapshot getLatestSnapshot() {
        return latest != null ? latest : buildSnapshot();
    }

    public Map<String, Object> getLatestSnapshotAsMap() {
        return toMap(getLatestSnapshot());
    }

    public Map<String, Object> toMap(Snapshot s) {
        return Map.of(
                "tps", new double[]{s.tps1(), s.tps5(), s.tps15()},
                "mspt", s.mspt(),
                "players", s.players(),
                "uptime", System.currentTimeMillis() - startMs,
                "memoryUsedMb", s.memoryUsedMb()
        );
    }

    private Snapshot buildSnapshot() {
        double[] tps = Bukkit.getServer().getTPS();
        double mspt = toMspt(Bukkit.getServer().getTickTimes());
        int players = Bukkit.getOnlinePlayers().size();
        Runtime rt = Runtime.getRuntime();
        long memMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        return new Snapshot(
                tps.length > 0 ? tps[0] : 20.0,
                tps.length > 1 ? tps[1] : 20.0,
                tps.length > 2 ? tps[2] : 20.0,
                mspt, players, memMb
        );
    }

    private double toMspt(long[] tickTimes) {
        if (tickTimes == null || tickTimes.length == 0) return 0.0;
        long sum = 0;
        for (long t : tickTimes) sum += t;
        return (sum / (double) tickTimes.length) / 1_000_000.0;
    }
}
