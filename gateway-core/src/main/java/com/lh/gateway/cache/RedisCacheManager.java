package com.lh.gateway.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class RedisCacheManager {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheManager.class);
    private static final String PREFIX = "cache:llm:";
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public RedisCacheManager(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<String> get(String key) {
        return redisTemplate.opsForValue().get(PREFIX + key)
                .doOnNext(v -> log.debug("Redis cache hit: {}", key))
                .onErrorResume(error -> {
                    // 缓存基础设施故障不能阻断 LLM 主调用链路。
                    log.warn("Redis cache read failed, fallback to upstream: key={}, err={}",
                            key, error.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> put(String key, String value, long ttlSeconds) {
        return redisTemplate.opsForValue()
                .set(PREFIX + key, value, Duration.ofSeconds(ttlSeconds))
                .then();
    }
}
