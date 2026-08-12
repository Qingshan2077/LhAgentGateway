package com.lh.gateway.cache;

import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class MultiLevelCacheManager implements CacheManager {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheManager.class);
    private static final long DEFAULT_TTL = 300;
    private static final long EMPTY_TTL = 30;

    private final LocalCacheManager localCache;
    private final RedisCacheManager redisCache;
    private final BloomFilterHelper bloomFilter;

    public MultiLevelCacheManager(LocalCacheManager localCache,
                                   RedisCacheManager redisCache,
                                   BloomFilterHelper bloomFilter) {
        this.localCache = localCache;
        this.redisCache = redisCache;
        this.bloomFilter = bloomFilter;
    }

    @Override
    public Mono<LlmResponse> get(String cacheKey) {
        if (!bloomFilter.mightContain(cacheKey)) return Mono.empty();

        return localCache.get(cacheKey)
                .switchIfEmpty(Mono.defer(() ->
                        redisCache.get(cacheKey)
                                .doOnNext(v -> localCache.put(cacheKey, v))));
    }

    @Override
    public Mono<Void> put(String cacheKey, LlmResponse response, long ttlSeconds) {
        bloomFilter.put(cacheKey);
        return redisCache.put(cacheKey, response, ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL)
                .doOnSuccess(v -> localCache.put(cacheKey, response));
    }

    @Override
    public Mono<Boolean> mightContain(String cacheKey) {
        return Mono.just(bloomFilter.mightContain(cacheKey));
    }

    public Mono<Void> putEmpty(String cacheKey) {
        bloomFilter.put(cacheKey);
        return redisCache.put(cacheKey, null, EMPTY_TTL);
    }
}
