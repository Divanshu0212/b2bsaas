package com.salespipe.identity;

import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.RefreshToken;
import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.RefreshTokenRepository;
import com.salespipe.identity.infra.RefreshTokenService;
import com.salespipe.identity.infra.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    RefreshTokenRepository repo;
    UserRepository users;
    StringRedisTemplate redis;
    RefreshTokenService svc;
    Map<String, RefreshToken> store;
    User user;

    @BeforeEach
    void setup() {
        repo = mock(RefreshTokenRepository.class);
        users = mock(UserRepository.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        JwtProperties props = new JwtProperties();
        props.setRefreshTtlSeconds(1000);
        svc = new RefreshTokenService(repo, users, redis, props);

        store = new HashMap<>();
        UUID orgId = UUID.randomUUID();
        user = new User(UUID.randomUUID(), orgId, "a@b.com", "hash", Role.ADMIN);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(repo.save(any())).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            store.put(t.getTokenHash(), t);
            return t;
        });
        when(repo.findByTokenHash(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    }

    @Test
    void reusingUsedTokenRevokesFamily() {
        var issued = svc.issue(user);
        var rotated = svc.rotate(issued.rawToken()); // original now used=true

        // Replaying the original raw token => reuse => family revoked.
        assertThatThrownBy(() -> svc.rotate(issued.rawToken()))
            .isInstanceOf(RefreshTokenService.TokenReuseException.class);
        verify(repo).deleteByFamilyId(any(UUID.class));
    }

    @Test
    void unknownTokenIsInvalid() {
        assertThatThrownBy(() -> svc.rotate("nope"))
            .isInstanceOf(RefreshTokenService.InvalidTokenException.class);
    }
}
