package com.lh.gateway.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lh.gateway.circuitbreaker.CircuitBreakerFactory;
import com.lh.gateway.circuitbreaker.FallbackHandler;
import com.lh.gateway.circuitbreaker.ProviderCircuitBreaker;
import com.lh.gateway.config.LlmProperties;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import com.lh.gateway.model.ProviderConfig;
import com.lh.gateway.monitor.CustomMetrics;
import com.lh.gateway.router.LeastLatencyRouter;
import com.lh.gateway.router.RouterFactory;
import com.lh.gateway.router.RoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * 路由选择过滤器（GlobalFilter，order = +48）
 *
 * <p>在 LLM 请求路径上（请求上下文由 {@link LlmRequestContextFilter} 独立准备，与缓存开关无关）：
 * <ol>
 *   <li>选择 Provider：请求头 {@code X-Provider} 优先（指定即走指定供应商）；
 *       否则按策略（{@code llm.router.strategy}，可被 {@code X-Router-Strategy} 头覆盖）从配置的
 *       {@code llm.providers} 列表中选择——加权轮询 / 最小延迟 / 一致性哈希</li>
 *   <li>熔断检查：Provider 熔断（OPEN）时调用 {@link FallbackHandler} 切换备选或降级响应</li>
 *   <li>设置转发目标（GATEWAY_REQUEST_URL_ATTR = provider.base-url + path），
 *       由 NettyRoutingFilter 转发到选中 Provider</li>
 *   <li>转发完成后记录延迟到 {@link LeastLatencyRouter}（供最小延迟策略使用）</li>
 * </ol></p>
 *
 * <p>未配置多 Provider 时直接放行，走默认路由 {@code llm.upstream.uri}。</p>
 */
@Slf4j
@Component
public class RouterFilter implements GlobalFilter, Ordered {

    public static final String PROVIDER_ATTR = "selectedProvider";
    /**
     * RouterFilter runs before Spring's RouteToRequestUrlFilter so cache and rate-limit filters can
     * observe the selected provider. Spring later rebuilds GATEWAY_REQUEST_URL_ATTR from the static
     * route, therefore ProviderRequestUrlFilter reapplies this dynamic target after that step.
     */
    public static final String PROVIDER_TARGET_URL_ATTR = "selectedProviderTargetUrl";

    private final LlmProperties llmProperties;
    private final RouterFactory routerFactory;
    private final CircuitBreakerFactory breakerFactory;
    private final FallbackHandler fallbackHandler;
    private final LeastLatencyRouter leastLatencyRouter;
    private final CustomMetrics customMetrics;
    private final ObjectMapper objectMapper;

    public RouterFilter(LlmProperties llmProperties,
                        RouterFactory routerFactory,
                        CircuitBreakerFactory breakerFactory,
                        FallbackHandler fallbackHandler,
                        LeastLatencyRouter leastLatencyRouter,
                        CustomMetrics customMetrics,
                        ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.routerFactory = routerFactory;
        this.breakerFactory = breakerFactory;
        this.fallbackHandler = fallbackHandler;
        this.leastLatencyRouter = leastLatencyRouter;
        this.customMetrics = customMetrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 路由入口只由 API 路径决定，不再依赖 CacheFilter 或请求体是否成功解析。
        if (!isLlmApi(exchange)) {
            return chain.filter(exchange);
        }

        LlmRequest request = exchange.getAttribute(LlmRequestContextFilter.LLM_REQUEST_ATTR);
        RoutingContext routingContext = exchange.getAttribute(LlmRequestContextFilter.ROUTING_CONTEXT_ATTR);
        if (routingContext == null) {
            routingContext = new RoutingContext(
                    request != null ? request.getModel() : null,
                    exchange.getAttribute("appKey"),
                    exchange.getRequest().getHeaders().getFirst("X-Session-Id"),
                    exchange.getAttribute("requestId"));
        }

        List<ProviderConfig> providers = enabledProviders();
        if (providers.isEmpty()) {
            return chain.filter(exchange);
        }

        // 重试时复用已选 Provider，确保同一次逻辑请求不会因策略计数变化切换上游。
        String preselectedProvider = exchange.getAttribute(PROVIDER_ATTR);
        // 请求头显式指定 Provider → 优先使用；否则按策略选择
        String headerProvider = exchange.getRequest().getHeaders().getFirst("X-Provider");
        Mono<String> selection;
        if (preselectedProvider != null) {
            selection = Mono.just(preselectedProvider);
        } else if (headerProvider != null && !headerProvider.isBlank()) {
            selection = Mono.just(headerProvider);
        } else {
            selection = routerFactory.getStrategy(resolveStrategy(exchange))
                    .select(providers, routingContext);
        }

        return selection.flatMap(provider -> route(exchange, chain, provider, providers, request));
    }

    private Mono<Void> route(ServerWebExchange exchange, GatewayFilterChain chain,
                             String provider, List<ProviderConfig> providers, LlmRequest request) {
        long start = System.nanoTime();
        ProviderConfig target = providers.stream()
                .filter(p -> p.getName().equalsIgnoreCase(provider))
                .findFirst()
                .orElse(null);
        // 显式指定未知 Provider 时不能静默绕过动态路由。
        if (target == null) {
            log.warn("Provider '{}' is not enabled or configured", provider);
            exchange.getAttributes().put(PROVIDER_ATTR, provider);
            return observe(exchange, provider, request, start,
                    writeUnknownProviderResponse(exchange, provider), false);
        }

        exchange.getAttributes().put(PROVIDER_ATTR, provider);

        // 熔断检查：OPEN 时切换备选 Provider 或返回降级响应
        ProviderCircuitBreaker breaker = breakerFactory.getBreaker(provider);
        if (!breaker.isCallAllowed()) {
            log.warn("Circuit breaker OPEN for provider: {}, switching to fallback", provider);
            if (!isChatCompletion(exchange)) {
                return observe(exchange, provider, request, start,
                        writeCircuitOpenResponse(exchange), false);
            }
            if (request == null) {
                return observe(exchange, provider, null, start,
                        writeInvalidRequestResponse(exchange), false);
            }
            Mono<Void> fallback = fallbackHandler.fallback(provider, request)
                    .flatMap(response -> writeFallbackResponse(exchange, response));
            return observe(exchange, provider, request, start, fallback, false);
        }

        // 设置转发目标：provider.base-url + 请求路径
        String path = exchange.getRequest().getPath().value();
        String rawQuery = exchange.getRequest().getURI().getRawQuery();
        String targetUrl = target.getBaseUrl() + path
                + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery);
        URI targetUri = URI.create(targetUrl);
        exchange.getAttributes().put(PROVIDER_TARGET_URL_ATTR, targetUri);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);
        log.debug("Route selected: provider={}, target={}", provider, targetUri);

        // 转发，完成后记录延迟（供最小延迟策略统计）+ 监控指标 + 调用日志发 MQ
        return observe(exchange, provider, request, start, chain.filter(exchange), true);
    }

    private Mono<Void> observe(ServerWebExchange exchange,
                               String provider,
                               LlmRequest request,
                               long start,
                               Mono<Void> invocation,
                               boolean recordProviderLatency) {
        return invocation
                .doOnSuccess(v -> {
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    HttpStatus status = resolveStatus(exchange);
                    boolean success = status == null || status.is2xxSuccessful();
                    if (recordProviderLatency && success && !"HIT".equalsIgnoreCase(
                            exchange.getResponse().getHeaders().getFirst("X-Cache"))) {
                        leastLatencyRouter.recordLatency(provider, latencyMs);
                    }
                    recordMetrics(exchange, provider, request, latencyMs, null);
                    log.debug("Route latency: provider={}, {}ms", provider, latencyMs);
                })
                .doOnError(error -> {
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    recordMetrics(exchange, provider, request, latencyMs, error);
                });
    }

    /** 埋点：请求计数 + 延迟 + token 消耗 */
    private void recordMetrics(ServerWebExchange exchange, String provider,
                               LlmRequest request, long latencyMs, Throwable error) {
        try {
            HttpStatus status = resolveStatus(exchange);
            String statusName = error != null
                    ? "UPSTREAM_ERROR"
                    : status != null ? status.name() : HttpStatus.OK.name();
            customMetrics.recordRequest(provider,
                    request != null ? request.getModel() : "unknown", statusName);
            customMetrics.recordLatency(provider, latencyMs);
            Integer totalTokens = exchange.getAttribute("llmTotalTokens");
            if (totalTokens != null && totalTokens > 0) {
                customMetrics.recordTokenUsage(provider, totalTokens);
            }
        } catch (Exception e) {
            log.warn("Failed to record metrics: {}", e.getMessage());
        }
    }

    private HttpStatus resolveStatus(ServerWebExchange exchange) {
        if (exchange.getResponse().getStatusCode() == null) {
            return null;
        }
        return HttpStatus.resolve(exchange.getResponse().getStatusCode().value());
    }

    private List<ProviderConfig> enabledProviders() {
        return llmProperties.getProviders().stream()
                .filter(ProviderConfig::isEnabled)
                .filter(ProviderConfig::isRoutingEnabled)
                .filter(p -> p.getName() != null && !p.getName().isBlank())
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .toList();
    }

    private String resolveStrategy(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("X-Router-Strategy");
        return header != null ? header : llmProperties.getRouter().getStrategy();
    }

    private boolean isChatCompletion(ServerWebExchange exchange) {
        return "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && exchange.getRequest().getPath().value().endsWith("/v1/chat/completions");
    }

    private boolean isLlmApi(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith("/v1/");
    }

    private Mono<Void> writeUnknownProviderResponse(ServerWebExchange exchange, String provider) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "error", "Unknown or disabled provider: " + provider));
        } catch (Exception error) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** 非聊天接口无法使用 Chat Adapter 降级，返回明确的熔断状态。 */
    private Mono<Void> writeCircuitOpenResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("X-Circuit-Breaker", "OPEN");
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "error", "Provider circuit is open and this endpoint has no compatible fallback adapter"));
        } catch (Exception error) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** 无法解析的请求不能安全转换后发送给备选 Provider，明确返回客户端错误。 */
    private Mono<Void> writeInvalidRequestResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "error", "Invalid LLM request body; fallback requires a parseable request"));
        } catch (Exception error) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** 熔断降级响应：带 X-Circuit-Breaker: OPEN 标记 */
    private Mono<Void> writeFallbackResponse(ServerWebExchange exchange, LlmResponse response) {
        ServerHttpResponse httpResponse = exchange.getResponse();
        httpResponse.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        httpResponse.getHeaders().add("X-Circuit-Breaker", "OPEN");
        byte[] body;
        try {
            if (response.getUsage() != null) {
                exchange.getAttributes().put("llmPromptTokens",
                        response.getUsage().getPromptTokens() != null ? response.getUsage().getPromptTokens() : 0);
                exchange.getAttributes().put("llmCompletionTokens",
                        response.getUsage().getCompletionTokens() != null ? response.getUsage().getCompletionTokens() : 0);
                exchange.getAttributes().put("llmTotalTokens",
                        response.getUsage().getTotalTokens() != null ? response.getUsage().getTotalTokens() : 0);
            }
            body = objectMapper.writeValueAsBytes(response);
        } catch (Exception e) {
            log.error("Serialize fallback response failed", e);
            httpResponse.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return httpResponse.setComplete();
        }
        DataBuffer buffer = httpResponse.bufferFactory().wrap(body);
        return httpResponse.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 48;
    }
}
