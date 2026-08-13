package com.lh.gateway.gateway.filter;

import com.lh.gateway.circuitbreaker.CircuitBreakerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在真实上游调用外围统一记录熔断结果。
 *
 * <p>同时覆盖普通响应、空响应体、流式响应、关闭缓存，以及连接拒绝、DNS 失败、
 * 连接/读取超时等无 HTTP 状态码异常。每次过滤器执行最多回喂一次。</p>
 */
@Slf4j
@Component
public class CircuitBreakerResultFilter implements GlobalFilter, Ordered {

    private static final String LLM_CHAT_PATH = "/v1/chat/completions";
    private final CircuitBreakerFactory breakerFactory;

    @Value("${llm.upstream.provider:openai}")
    private String defaultProvider;

    public CircuitBreakerResultFilter(CircuitBreakerFactory breakerFactory) {
        this.breakerFactory = breakerFactory;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!exchange.getRequest().getPath().value().endsWith(LLM_CHAT_PATH)) {
            return chain.filter(exchange);
        }

        String provider = resolveProvider(exchange);
        AtomicBoolean recorded = new AtomicBoolean(false);
        return chain.filter(exchange)
                .doOnSuccess(ignored -> {
                    var status = exchange.getResponse().getStatusCode();
                    // WebFlux 未显式设置状态码时按默认 200 处理；异常路径由 doOnError 记录失败。
                    record(provider, status == null || status.is2xxSuccessful(), recorded);
                })
                .doOnError(error -> {
                    record(provider, false, recorded);
                    log.warn("Upstream call failed before HTTP response: provider={}, error={}",
                            provider, error.getMessage());
                });
    }

    private void record(String provider, boolean success, AtomicBoolean recorded) {
        if (recorded.compareAndSet(false, true)) {
            breakerFactory.getBreaker(provider).recordResult(success);
            log.debug("Record circuit result: provider={}, success={}", provider, success);
        }
    }

    private String resolveProvider(ServerWebExchange exchange) {
        String selected = exchange.getAttribute(RouterFilter.PROVIDER_ATTR);
        if (hasText(selected)) {
            return normalize(selected);
        }
        String requested = exchange.getRequest().getHeaders().getFirst("X-Provider");
        if (hasText(requested)) {
            return normalize(requested);
        }
        return hasText(defaultProvider) ? normalize(defaultProvider) : "openai";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String provider) {
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 65;
    }
}
