package com.lh.gateway.limiter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private RedisScript<Long> rateLimitScript;

    public RedisTokenBucketRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        try {
            var resource = new ClassPathResource("lua/token_bucket.lua");
            String scriptContent = new String(resource.getInputStream().readAllBytes());
            ((DefaultRedisScript<Long>) rateLimitScript).setScriptText(scriptContent);
            ((DefaultRedisScript<Long>) rateLimitScript).setResultType(Long.class);
            log.info("Token bucket Lua script loaded");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Lua script", e);
        }
    }

    @Override
    public Mono<RateLimitResult> tryAcquire(String key, int capacity, int rate, int cost) {
        String bucketKey = "rate_limit:bucket:" + key;
        long now = System.currentTimeMillis();

        return redisTemplate.execute(
                        rateLimitScript,
                        List.of(bucketKey),
                        String.valueOf(capacity),
                        String.valueOf(rate),
                        String.valueOf(now),
                        String.valueOf(cost)
                )
                .map(result -> {
                    if (result == 1L) return RateLimitResult.ALLOWED;
                    long retryAfterMs = (cost * 1000L) / Math.max(rate, 1);
                    return RateLimitResult.denied(retryAfterMs);
                })
                .onErrorResume(e -> {
                    log.warn("Redis error, fallback allow: {}", e.getMessage());
                    return Mono.just(RateLimitResult.ALLOWED);
                });
    }
}
