package com.diagramio.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisLoginRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static RedisLoginRateLimiter limiter;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        limiter = new RedisLoginRateLimiter(redis);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void sixthAttemptIsBlockedWhileAnotherIpHasIndependentQuota() {
        String ip = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.retryAfterSeconds(ip)).isZero();
        }
        assertThat(limiter.retryAfterSeconds(ip)).isBetween(1L, 60L);
        assertThat(limiter.retryAfterSeconds(UUID.randomUUID().toString())).isZero();
        assertThat(redis.getExpire("diagramio:ratelimit:login:" + ip)).isBetween(1L, 60L);
    }

    @Test
    void expiredAttemptsAreRemovedAndDoNotConsumeQuota() throws Exception {
        String ip = UUID.randomUUID().toString();
        String key = "diagramio:ratelimit:login:" + ip;
        long now = Long.parseLong(REDIS.execInContainer("redis-cli", "--raw", "TIME")
                .getStdout().split("\\s+")[0]) * 1000;
        for (int i = 0; i < 5; i++) {
            redis.opsForZSet().add(key, "old-" + i, now - 60000);
        }
        assertThat(limiter.retryAfterSeconds(ip)).isZero();
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(1);
    }

    @Test
    void slidingWindowKeepsRecentAttemptsWhenOlderOnesExpire() throws Exception {
        String ip = UUID.randomUUID().toString();
        String key = "diagramio:ratelimit:login:" + ip;
        long now = Long.parseLong(REDIS.execInContainer("redis-cli", "--raw", "TIME")
                .getStdout().split("\\s+")[0]) * 1000;
        redis.opsForZSet().add(key, "expired", now - 61000);
        for (int i = 0; i < 4; i++) {
            redis.opsForZSet().add(key, "recent-" + i, now - 1000);
        }
        assertThat(limiter.retryAfterSeconds(ip)).isZero();
        assertThat(limiter.retryAfterSeconds(ip)).isBetween(1L, 60L);
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(5);
    }

    @Test
    void concurrentRequestsAcrossInstancesAdmitExactlyFiveAttempts() throws Exception {
        String ip = UUID.randomUUID().toString();
        RedisLoginRateLimiter secondInstance = new RedisLoginRateLimiter(redis);
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                RedisLoginRateLimiter instance = i % 2 == 0 ? limiter : secondInstance;
                calls.add(() -> instance.retryAfterSeconds(ip));
            }
            int allowed = 0;
            for (var result : executor.invokeAll(calls)) {
                if (result.get() == 0) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
        }
    }
}
