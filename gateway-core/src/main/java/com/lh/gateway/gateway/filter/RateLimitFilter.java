package com.lh.gateway.gateway.filter;

import com.lh.gateway.limiter.RateLimiter;
import com.lh.gateway.limiter.RateLimitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 限流过滤器
 *
 * <p>在请求进入网关后立即执行限流判定。
 * 三层限流：全局 → AppKey → Provider-模型。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimiter rateLimiter;

    /** 全局限流 (500 容量, 100/s 速率) */
    private static final int GLOBAL_CAPACITY = 500;
    private static final int GLOBAL_RATE = 100;

    /** AppKey 级别限流 (100 容量, 20/s 速率) */
    private static final int APP_KEY_CAPACITY = 100;
    private static final int APP_KEY_RATE = 20;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String appKey = exchange.getAttribute("appKey");
        String provider = exchange.getRequest().getHeaders().getFirst("X-Provider");

        // 1. 全局限流
        return rateLimiter.tryAcquire("global", GLOBAL_CAPACITY, GLOBAL_RATE, 1)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        log.warn("Global rate limit exceeded");
                        return denyRequest(exchange, result);
                    }
                    // 2. AppKey 级别限流
                    if (appKey != null) {
                        return rateLimiter.tryAcquire("app:" + appKey, APP_KEY_CAPACITY, APP_KEY_RATE, 1)
                                .flatMap(appResult -> {
                                    if (!appResult.isAllowed()) {
                                        log.warn("AppKey rate limit exceeded: {}", appKey);
                                        return denyRequest(exchange, appResult);
                                    }
                                    return chain.filter(exchange);
                                });
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> denyRequest(ServerWebExchange exchange, RateLimitResult result) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After",
                String.valueOf(result.getRetryAfterMs() / 1000));
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
