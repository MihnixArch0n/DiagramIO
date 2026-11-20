package com.diagramio.infrastructure.security;

import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisLoginRateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = script();

    private static DefaultRedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/login-rate-limit.lua"));
        script.setResultType(Long.class);
        return script;
    }
    private final StringRedisTemplate redis;

    public RedisLoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns zero when admitted, otherwise the seconds until the next available attempt. */
    public long retryAfterSeconds(String ip) {
        Long result = redis.execute(SCRIPT, List.of("diagramio:ratelimit:login:" + ip),
                UUID.randomUUID().toString());
        if (result == null || result < 0) {
            throw new IllegalStateException("Redis returned an invalid rate limit result");
        }
        return result;
    }
}
