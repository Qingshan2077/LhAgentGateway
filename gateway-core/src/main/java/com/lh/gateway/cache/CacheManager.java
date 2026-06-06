package com.lh.gateway.cache;

import com.lh.gateway.model.LlmResponse;
import reactor.core.publisher.Mono;

/**
 * 缓存管理器接口
 */
public interface CacheManager {

    /** 获取缓存 */
    Mono<LlmResponse> get(String cacheKey);

    /** 写入缓存 */
    Mono<Void> put(String cacheKey, LlmResponse response, long ttlSeconds);

    /** 判断 Key 是否可能存在 */
    Mono<Boolean> mightContain(String cacheKey);
}
