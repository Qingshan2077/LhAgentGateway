package com.lh.gateway.cache;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class MultiLevelCacheManager implements CacheManager {

    private static final long DEFAULT_TTL = 300;
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
    public Mono<String> get(String cacheKey) {
        return localCache.get(cacheKey)
                .switchIfEmpty(Mono.defer(() ->
                        bloomFilter.mightContain(cacheKey)
                                .flatMap(mightContain -> {
                                    if (!mightContain) {
                                        return Mono.empty();
                                    }
                                    return redisCache.get(cacheKey)
                                            .doOnNext(v -> localCache.put(cacheKey, v));
                                })));
    }

    @Override
    public Mono<Void> put(String cacheKey, String responseJson, long ttlSeconds) {
        return bloomFilter.put(cacheKey)
                .then(redisCache.put(cacheKey, responseJson, ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL))
                .doOnSuccess(v -> localCache.put(cacheKey, responseJson));
    }

    @Override
    public Mono<Boolean> mightContain(String cacheKey) {
        return bloomFilter.mightContain(cacheKey);
    }
}
