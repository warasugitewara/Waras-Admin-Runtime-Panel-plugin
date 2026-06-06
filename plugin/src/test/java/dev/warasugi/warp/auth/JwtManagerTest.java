package dev.warasugi.warp.auth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import static org.junit.jupiter.api.Assertions.*;

class JwtManagerTest {

    private SecretKey testKey() {
        return Jwts.SIG.HS256.key().build();
    }

    @Test
    void issue_thenIsValid_returnsTrue() {
        JwtManager jwt = new JwtManager(testKey(), 8 * 3600_000L);
        assertTrue(jwt.isValid(jwt.issue()));
    }

    @Test
    void isValid_withExpiredToken_returnsFalse() {
        JwtManager jwt = new JwtManager(testKey(), -1L);
        assertFalse(jwt.isValid(jwt.issue()));
    }

    @Test
    void isValid_withGarbage_returnsFalse() {
        JwtManager jwt = new JwtManager(testKey(), 3600_000L);
        assertFalse(jwt.isValid("garbage.token.here"));
    }

    @Test
    void isValid_withDifferentKey_returnsFalse() {
        String token = new JwtManager(testKey(), 3600_000L).issue();
        assertFalse(new JwtManager(testKey(), 3600_000L).isValid(token));
    }
}
