package dev.warasugi.warp.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;

public class JwtManager {
    private final SecretKey key;
    private final long expirationMs;

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
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
