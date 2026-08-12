package com.lh.gateway.gateway.filter;

import com.lh.gateway.limiter.RateLimiter;
import com.lh.gateway.limiter.RateLimitResult;
import com.lh.gateway.monitor.CustomMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>三层限流：全局 → AppKey → Provider，任一层拒绝即返回 429 + Retry-After。
 * 各层容量与速率均可通过 application.yml 的 {@code llm.rate-limit.*} 配置，
 * 压测时可临时调大阈值避免流量被限流器拦截。</p>
 *
 * <p>key 设计：全局 {@code rate_limit:bucket:global}；
 * AppKey 层 {@code rate_limit:bucket:app:{appKey}}；
 * Provider 层 {@code rate_limit:bucket:provider:{provider}}（来自 X-Provider 请求头）。</p>
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimiter rateLimiter;
    private final CustomMetrics customMetrics;

    /** 全局限流（默认 500 容量, 100/s 速率） */
    @Value("${llm.rate-limit.global-capacity:500}")
    private int globalCapacity;
    @Value("${llm.rate-limit.global-rate:100}")
    private int globalRate;

    /** AppKey 级别限流（默认 100 容量, 20/s 速率） */
    @Value("${llm.rate-limit.app-key-capacity:100}")
    private int appKeyCapacity;
    @Value("${llm.rate-limit.app-key-rate:20}")
    private int appKeyRate;

    /** Provider 级别限流（默认 200 容量, 50/s 速率） */
    @Value("${llm.rate-limit.provider-capacity:200}")
    private int providerCapacity;
    @Value("${llm.rate-limit.provider-rate:50}")
    private int providerRate;

    public RateLimitFilter(RateLimiter rateLimiter, CustomMetrics customMetrics) {
        this.rateLimiter = rateLimiter;
        this.customMetrics = customMetrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String appKey = exchange.getAttribute("appKey");
        String provider = exchange.getRequest().getHeaders().getFirst("X-Provider");

        // 1. 全局限流
        return rateLimiter.tryAcquire("global", globalCapacity, globalRate, 1)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        log.warn("Global rate limit exceeded");
                        return denyRequest(exchange, result, "global");
                    }
                    return checkAppKeyLevel(exchange, chain, appKey, provider);
                });
    }

    /** 2. AppKey 级别限流（无 AppKey 直接跳过） */
    private Mono<Void> checkAppKeyLevel(ServerWebExchange exchange, GatewayFilterChain chain,
                                        String appKey, String provider) {
        if (appKey == null) {
            return checkProviderLevel(exchange, chain, provider);
        }
        return rateLimiter.tryAcquire("app:" + appKey, appKeyCapacity, appKeyRate, 1)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        log.warn("AppKey rate limit exceeded: {}", appKey);
                        return denyRequest(exchange, result, "app");
                    }
                    return checkProviderLevel(exchange, chain, provider);
                });
    }

    /** 3. Provider 级别限流（无 X-Provider 头直接跳过） */
    private Mono<Void> checkProviderLevel(ServerWebExchange exchange, GatewayFilterChain chain,
                                          String provider) {
        if (provider == null) {
            return chain.filter(exchange);
        }
        return rateLimiter.tryAcquire("provider:" + provider, providerCapacity, providerRate, 1)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        log.warn("Provider rate limit exceeded: {}", provider);
                        return denyRequest(exchange, result, "provider");
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> denyRequest(ServerWebExchange exchange, RateLimitResult result, String level) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After",
                String.valueOf(result.getRetryAfterMs() / 1000));
        exchange.getAttributes().put("rateLimitLevel", level);
        customMetrics.recordRateLimit(level);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
