package com.lh.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

@Component
public class LocalCacheManager {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheManager.class);
    private final Cache<String, LlmResponse> cache;

    public LocalCacheManager() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    public Mono<LlmResponse> get(String key) {
        LlmResponse value = cache.getIfPresent(key);
        if (value != null) log.debug("Local cache hit: {}", key);
        return Mono.justOrEmpty(value);
    }

    public void put(String key, LlmResponse value) {
        cache.put(key, value);
    }
}
