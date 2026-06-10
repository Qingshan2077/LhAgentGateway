package com.lh.gateway.cache;

import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class RedisCacheManager {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheManager.class);
    private static final String PREFIX = "cache:llm:";
    private final ReactiveRedisTemplate<String, LlmResponse> redisTemplate;

    public RedisCacheManager(ReactiveRedisTemplate<String, LlmResponse> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<LlmResponse> get(String key) {
        return redisTemplate.opsForValue().get(PREFIX + key)
                .doOnNext(v -> log.debug("Redis cache hit: {}", key));
    }

    public Mono<Void> put(String key, LlmResponse value, long ttlSeconds) {
        return redisTemplate.opsForValue()
                .set(PREFIX + key, value, Duration.ofSeconds(ttlSeconds))
                .then();
    }
}
