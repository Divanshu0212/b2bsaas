package com.salespipe.emailtracking.security;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dedicated Lettuce {@link RedisClient}/connection for Bucket4j's Redis proxy manager
 * (see {@link RateLimiter}). The rest of this codebase talks to Redis via Spring Data
 * Redis's {@code StringRedisTemplate}/{@code LettuceConnectionFactory} (see
 * {@code RefreshTokenService}), but bucket4j-redis's {@code LettuceBasedProxyManager}
 * needs a raw {@code StatefulRedisConnection<byte[], byte[]>} (it does its own binary
 * (de)serialization of bucket state) rather than a Spring-wrapped connection, so this
 * is a small, separate client using the same connection coordinates
 * ({@code spring.data.redis.host}/{@code port}, matching {@code application.yml}).
 */
@Configuration
public class RateLimiterConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Bean(destroyMethod = "shutdown")
    public RedisClient trackingRedisClient() {
        return RedisClient.create(RedisURI.builder()
            .withHost(redisHost)
            .withPort(redisPort)
            .build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<byte[], byte[]> trackingRedisConnection(RedisClient client) {
        return client.connect(ByteArrayCodec.INSTANCE);
    }
}
