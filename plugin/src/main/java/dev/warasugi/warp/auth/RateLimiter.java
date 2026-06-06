package dev.warasugi.warp.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimiter {
    private final int maxAttempts;
    private final long windowMs;
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> windowStart = new ConcurrentHashMap<>();

    public RateLimiter(int maxAttempts, long windowMs) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
    }

    public boolean isAllowed(String ip) {
        long now = System.currentTimeMillis();
        windowStart.compute(ip, (k, v) -> {
            if (v == null || now - v > windowMs) {
                counts.put(k, new AtomicInteger(0));
                return now;
            }
            return v;
        });
        return counts.get(ip).incrementAndGet() <= maxAttempts;
    }

    public void reset(String ip) {
        counts.remove(ip);
        windowStart.remove(ip);
    }
}
