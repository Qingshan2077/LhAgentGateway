package com.lh.gateway.cache;

import reactor.core.publisher.Mono;

/**
 * 缓存管理器接口
 */
public interface CacheManager {

    /** 获取缓存 */
    Mono<String> get(String cacheKey);

    /** 写入缓存 */
    Mono<Void> put(String cacheKey, String responseJson, long ttlSeconds);

    /** 判断 Key 是否可能存在 */
    Mono<Boolean> mightContain(String cacheKey);
}
