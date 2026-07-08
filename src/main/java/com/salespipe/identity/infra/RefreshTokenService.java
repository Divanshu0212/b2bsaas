package com.salespipe.identity.infra;

import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.RefreshToken;
import com.salespipe.identity.domain.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
    public static class TokenReuseException extends RuntimeException {
        public TokenReuseException(String m) { super(m); }
    }
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String m) { super(m); }
    }
    public record Issued(String rawToken, RefreshToken entity) {}
    public record RotationResult(User user, String rawToken, RefreshToken newToken) {}

    private static final SecureRandom RNG = new SecureRandom();
    private final RefreshTokenRepository repo;
    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final long refreshTtl;

    public RefreshTokenService(RefreshTokenRepository repo, UserRepository users,
                               StringRedisTemplate redis, JwtProperties props) {
        this.repo = repo; this.users = users; this.redis = redis;
        this.refreshTtl = props.getRefreshTtlSeconds();
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String redisKey(String tokenHash) { return "rt:" + tokenHash; }

    @Transactional
    public Issued issue(User user) {
        return issue(user, UUID.randomUUID(), null);
    }

    private Issued issue(User user, UUID familyId, UUID parentId) {
        String raw = randomToken();
        String h = hash(raw);
        RefreshToken t = new RefreshToken(UUID.randomUUID(), user.getOrgId(),
            user.getId(), familyId, h, parentId,
            OffsetDateTime.now().plusSeconds(refreshTtl));
        repo.save(t);
        redis.opsForValue().set(redisKey(h), user.getId().toString(),
            Duration.ofSeconds(refreshTtl));
        return new Issued(raw, t);
    }

    @Transactional(noRollbackFor = TokenReuseException.class)
    public RotationResult rotate(String rawToken) {
        String h = hash(rawToken);
        RefreshToken current = repo.findByTokenHash(h)
            .orElseThrow(() -> new InvalidTokenException("unknown token"));

        if (current.isUsed()) {
            // Reuse detected: nuke the whole family + Redis mirror.
            repo.deleteByFamilyId(current.getFamilyId());
            redis.delete(redisKey(h));
            throw new TokenReuseException("refresh token reuse; family revoked");
        }
        if (current.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidTokenException("expired");
        }

        current.markUsed();
        repo.save(current);
        redis.delete(redisKey(h));

        User user = users.findById(current.getUserId())
            .orElseThrow(() -> new InvalidTokenException("user gone"));
        Issued next = issue(user, current.getFamilyId(), current.getId());
        return new RotationResult(user, next.rawToken(), next.entity());
    }

    @Transactional
    public void revokeFamily(String rawToken) {
        repo.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            repo.deleteByFamilyId(t.getFamilyId());
            redis.delete(redisKey(t.getTokenHash()));
        });
    }
}
