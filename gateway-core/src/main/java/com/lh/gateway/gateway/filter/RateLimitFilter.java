package com.lh.gateway.gateway.filter;

import com.lh.gateway.limiter.RateLimiter;
import com.lh.gateway.limiter.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String appKey = exchange.getAttribute("appKey");

        return rateLimiter.tryAcquire("global", 500, 100, 1)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().add("Retry-After",
                                String.valueOf(result.getRetryAfterMs() / 1000));
                        return exchange.getResponse().setComplete();
                    }
                    if (appKey != null) {
                        return rateLimiter.tryAcquire("app:" + appKey, 100, 20, 1)
                                .flatMap(appResult -> {
                                    if (!appResult.isAllowed()) {
                                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                                        return exchange.getResponse().setComplete();
                                    }
                                    return chain.filter(exchange);
                                });
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
