package com.lh.gateway.gateway.filter;

import com.lh.gateway.limiter.RateLimitResult;
import com.lh.gateway.limiter.RateLimiter;
import com.lh.gateway.config.LlmProperties;
import com.lh.gateway.model.ProviderConfig;
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
 * Provider 级令牌桶限流。
 *
 * <p>该过滤器位于 {@link RouterFilter} 之后、Gateway 网络转发过滤器之前，
 * 因而优先使用路由策略写入的真实 Provider。没有动态路由结果时（例如流式请求
 * 或默认静态路由），依次回退到 {@code X-Provider} 请求头和
 * {@code llm.upstream.provider} 配置，确保所有真正访问上游的请求都进入
 * Provider 级令牌桶。</p>
 *
 * <p>缓存命中会在该过滤器之前直接返回，不会消耗供应商侧配额；RetryFilter
 * 重新执行下游链路时会再次经过本过滤器，因此每次真实上游重试都会消耗一个令牌。</p>
 */
@Slf4j
@Component
public class ProviderRateLimitFilter implements GlobalFilter, Ordered {

    private static final String LLM_CHAT_PATH = "/v1/chat/completions";

    private final RateLimiter rateLimiter;
    private final CustomMetrics customMetrics;
    private final LlmProperties llmProperties;

    @Value("${llm.rate-limit.provider-capacity:200}")
    private int providerCapacity;

    @Value("${llm.rate-limit.provider-rate:50}")
    private int providerRate;

    @Value("${llm.upstream.provider:openai}")
    private String defaultProvider;

    public ProviderRateLimitFilter(RateLimiter rateLimiter,
                                   CustomMetrics customMetrics,
                                   LlmProperties llmProperties) {
        this.rateLimiter = rateLimiter;
        this.customMetrics = customMetrics;
        this.llmProperties = llmProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isLlmUpstreamRequest(exchange)) {
            return chain.filter(exchange);
        }

        String provider = resolveProvider(exchange);
        if (provider == null) {
            log.error("Cannot resolve provider for upstream request: path={}",
                    exchange.getRequest().getPath().value());
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }

        int[] limits = resolveLimits(provider);
        return rateLimiter.tryAcquire(
                        "provider:" + provider, limits[0], limits[1], 1)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        return denyRequest(exchange, result, provider);
                    }
                    return chain.filter(exchange);
                });
    }

    private boolean isLlmUpstreamRequest(ServerWebExchange exchange) {
        return LLM_CHAT_PATH.equals(exchange.getRequest().getPath().value());
    }

    private String resolveProvider(ServerWebExchange exchange) {
        String selectedProvider = exchange.getAttribute(RouterFilter.PROVIDER_ATTR);
        if (hasText(selectedProvider)) {
            return normalize(selectedProvider);
        }

        String headerProvider = exchange.getRequest().getHeaders().getFirst("X-Provider");
        if (hasText(headerProvider)) {
            return normalize(headerProvider);
        }

        return hasText(defaultProvider) ? normalize(defaultProvider) : null;
    }

    /** 管理后台 rateLimitRpm 热更新后直接影响 Provider 桶；未配置时使用全局默认值。 */
    private int[] resolveLimits(String provider) {
        Integer rpm = llmProperties.getProviders().stream()
                .filter(config -> config.getName() != null && config.getName().equalsIgnoreCase(provider))
                .map(ProviderConfig::getRateLimitRpm)
                .filter(value -> value != null && value > 0)
                .findFirst()
                .orElse(null);
        if (rpm == null) {
            return new int[]{providerCapacity, providerRate};
        }
        int capacity = Math.max(1, rpm);
        int refillPerSecond = Math.max(1, (rpm + 59) / 60);
        return new int[]{capacity, refillPerSecond};
    }

    private Mono<Void> denyRequest(ServerWebExchange exchange,
                                   RateLimitResult result,
                                   String provider) {
        log.warn("Provider rate limit exceeded: {}", provider);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(
                "Retry-After", String.valueOf(toRetryAfterSeconds(result.getRetryAfterMs())));
        exchange.getAttributes().put("rateLimitLevel", "provider");
        exchange.getAttributes().put("rateLimitProvider", provider);
        customMetrics.recordRateLimit("provider");
        return exchange.getResponse().setComplete();
    }

    private long toRetryAfterSeconds(long retryAfterMs) {
        return Math.max(1L, (retryAfterMs + 999L) / 1000L);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String provider) {
        return provider.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 60;
    }
}
