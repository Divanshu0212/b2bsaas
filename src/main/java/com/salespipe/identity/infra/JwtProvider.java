package com.salespipe.identity.infra;

import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long accessTtl;

    public JwtProvider(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = props.getAccessTtlSeconds();
    }

    public String createAccessToken(UUID userId, UUID orgId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("org_id", orgId.toString())
            .claim("role", role.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTtl)))
            .signWith(key)
            .compact();
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    public UUID orgId(Claims c) { return UUID.fromString(c.get("org_id", String.class)); }
    public UUID userId(Claims c) { return UUID.fromString(c.getSubject()); }
    public Role role(Claims c) { return Role.valueOf(c.get("role", String.class)); }
}
