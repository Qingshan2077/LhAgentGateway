package com.lh.gateway.limiter;

import reactor.core.publisher.Mono;

/**
 * 限流器接口
 */
public interface RateLimiter {

    /**
     * 限流判定
     *
     * @param key      限流 Key
     * @param capacity 令牌桶容量
     * @param rate     令牌补充速率（个/秒）
     * @param cost     本次消耗令牌数
     */
    Mono<RateLimitResult> tryAcquire(String key, int capacity, int rate, int cost);
}
