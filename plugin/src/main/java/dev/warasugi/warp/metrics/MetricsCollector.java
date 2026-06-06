package dev.warasugi.warp.metrics;

import java.util.Map;

public class MetricsCollector {
    public record Snapshot(double tps1, double tps5, double tps15, double mspt,
                           int players, long uptime, long memoryUsed) {}

    public Snapshot getLatestSnapshot() {
        return new Snapshot(20.0, 20.0, 20.0, 50.0, 0,
                System.currentTimeMillis(), Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    }

    public Map<String, Object> getLatestSnapshotAsMap() {
        var s = getLatestSnapshot();
        return Map.of(
                "tps", new double[]{s.tps1(), s.tps5(), s.tps15()},
                "mspt", s.mspt(),
                "players", s.players(),
                "uptime", s.uptime(),
                "memoryUsedMb", s.memoryUsed() / 1024 / 1024
        );
    }
}
