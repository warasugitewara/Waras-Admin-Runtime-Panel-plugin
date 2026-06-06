package dev.warasugi.warp.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void isAllowed_underLimit_returnsTrue() {
        RateLimiter r = new RateLimiter(5, 60_000);
        for (int i = 0; i < 5; i++) {
            assertTrue(r.isAllowed("1.2.3.4"));
        }
    }

    @Test
    void isAllowed_overLimit_returnsFalse() {
        RateLimiter r = new RateLimiter(5, 60_000);
        for (int i = 0; i < 5; i++) r.isAllowed("1.1.1.1");
        assertFalse(r.isAllowed("1.1.1.1"));
    }

    @Test
    void reset_allowsAgain() {
        RateLimiter r = new RateLimiter(1, 60_000);
        r.isAllowed("10.0.0.1");
        assertFalse(r.isAllowed("10.0.0.1"));
        r.reset("10.0.0.1");
        assertTrue(r.isAllowed("10.0.0.1"));
    }
}
