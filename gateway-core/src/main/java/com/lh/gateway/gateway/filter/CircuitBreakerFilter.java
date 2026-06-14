package com.lh.gateway.gateway.filter;

import com.lh.gateway.circuitbreaker.CircuitBreakerFactory;
import com.lh.gateway.circuitbreaker.FallbackHandler;
import com.lh.gateway.circuitbreaker.ProviderCircuitBreaker;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 熔断过滤器
 *
 * <p>在转发到 Provider 前检查熔断状态。
 * 如果 Provider 已熔断，直接走降级逻辑（切换到备选 Provider）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerFilter implements GlobalFilter, Ordered {

    private final CircuitBreakerFactory breakerFactory;
    private final FallbackHandler fallbackHandler;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String provider = exchange.getRequest().getHeaders().getFirst("X-Provider");
        if (provider == null) {
            return chain.filter(exchange);
        }

        ProviderCircuitBreaker breaker = breakerFactory.getBreaker(provider);

        if (!breaker.isCallAllowed()) {
            log.warn("Circuit breaker OPEN for provider: {}, triggering fallback", provider);
            // 熔断 → 执行降级
            // 注意：这里简化了降级处理，完整的降级逻辑在 FallbackHandler 中
            exchange.getAttributes().put("circuitBreakerOpen", true);
            exchange.getAttributes().put("circuitBreakerProvider", provider);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
