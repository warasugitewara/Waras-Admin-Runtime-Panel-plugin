package dev.warasugi.warp.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JwtManager {
    private static final Logger LOGGER = Logger.getLogger(JwtManager.class.getName());
    private final SecretKey key;
    private final long expirationMs;
    private final AtomicLong issuedAfterEpochSeconds = new AtomicLong(0);

    JwtManager(SecretKey key, long expirationMs) {
        this.key = key;
        this.expirationMs = expirationMs;
    }

    public static JwtManager fromFile(Path keyFile, long expirationMs) throws IOException {
        SecretKey key;
        if (Files.exists(keyFile)) {
            byte[] bytes = Base64.getDecoder().decode(Files.readString(keyFile).strip());
            key = Keys.hmacShaKeyFor(bytes);
        } else {
            key = Jwts.SIG.HS256.key().build();
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()));
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                LOGGER.log(Level.WARNING, "Failed to restrict permissions on " + keyFile
                        + " (filesystem does not support POSIX permissions)", e);
            }
        }
        return new JwtManager(key, expirationMs);
    }

    public String issue() {
        return Jwts.builder()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public boolean isValid(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            long iatSeconds = claims.getIssuedAt().getTime() / 1000;
            return iatSeconds >= issuedAfterEpochSeconds.get();
        } catch (Exception e) {
            return false;
        }
    }

    public void revokeAll() {
        issuedAfterEpochSeconds.set(System.currentTimeMillis() / 1000);
    }
}
