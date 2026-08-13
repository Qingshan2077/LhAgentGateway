package com.lh.gateway.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lh.gateway.circuitbreaker.CircuitBreakerFactory;
import com.lh.gateway.circuitbreaker.FallbackHandler;
import com.lh.gateway.circuitbreaker.ProviderCircuitBreaker;
import com.lh.gateway.config.LlmProperties;
import com.lh.gateway.model.CallLog;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import com.lh.gateway.model.ProviderConfig;
import com.lh.gateway.monitor.CustomMetrics;
import com.lh.gateway.mq.LogProducer;
import com.lh.gateway.router.LeastLatencyRouter;
import com.lh.gateway.router.RouterFactory;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * 路由选择过滤器（GlobalFilter，order = +55）
 *
 * <p>在缓存未命中的转发路径上（CacheFilter 已解析请求体并存入 {@code llmRequest} attribute）：
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

    private final LlmProperties llmProperties;
    private final RouterFactory routerFactory;
    private final CircuitBreakerFactory breakerFactory;
    private final FallbackHandler fallbackHandler;
    private final LeastLatencyRouter leastLatencyRouter;
    private final LogProducer logProducer;
    private final CustomMetrics customMetrics;
    private final ObjectMapper objectMapper;

    public RouterFilter(LlmProperties llmProperties,
                        RouterFactory routerFactory,
                        CircuitBreakerFactory breakerFactory,
                        FallbackHandler fallbackHandler,
                        LeastLatencyRouter leastLatencyRouter,
                        LogProducer logProducer,
                        CustomMetrics customMetrics,
                        ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.routerFactory = routerFactory;
        this.breakerFactory = breakerFactory;
        this.fallbackHandler = fallbackHandler;
        this.leastLatencyRouter = leastLatencyRouter;
        this.logProducer = logProducer;
        this.customMetrics = customMetrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 非 LLM 请求或缓存命中路径（无 llmRequest attribute）直接放行
        LlmRequest request = exchange.getAttribute("llmRequest");
        if (request == null) {
            return chain.filter(exchange);
        }

        List<ProviderConfig> providers = enabledProviders();
        if (providers.isEmpty()) {
            return chain.filter(exchange);
        }

        // CacheFilter 已为缓存 Key 提前选择 Provider 时直接复用，避免策略计数器执行两次。
        String preselectedProvider = exchange.getAttribute(PROVIDER_ATTR);
        // 请求头显式指定 Provider → 优先使用；否则按策略选择
        String headerProvider = exchange.getRequest().getHeaders().getFirst("X-Provider");
        Mono<String> selection;
        if (preselectedProvider != null) {
            selection = Mono.just(preselectedProvider);
        } else if (headerProvider != null) {
            selection = Mono.just(headerProvider);
        } else {
            selection = routerFactory.getStrategy(resolveStrategy(exchange))
                    .select(providers, request.getModel());
        }

        return selection.flatMap(provider -> route(exchange, chain, provider, providers, request));
    }

    private Mono<Void> route(ServerWebExchange exchange, GatewayFilterChain chain,
                             String provider, List<ProviderConfig> providers, LlmRequest request) {
        ProviderConfig target = providers.stream()
                .filter(p -> p.getName().equalsIgnoreCase(provider))
                .findFirst()
                .orElse(null);
        // 指定了未配置的 Provider：走默认路由
        if (target == null) {
            log.warn("Provider '{}' not configured, using default route", provider);
            return chain.filter(exchange);
        }

        exchange.getAttributes().put(PROVIDER_ATTR, provider);

        // 熔断检查：OPEN 时切换备选 Provider 或返回降级响应
        ProviderCircuitBreaker breaker = breakerFactory.getBreaker(provider);
        if (!breaker.isCallAllowed()) {
            log.warn("Circuit breaker OPEN for provider: {}, switching to fallback", provider);
            return fallbackHandler.fallback(provider, request)
                    .flatMap(response -> writeFallbackResponse(exchange, response));
        }

        // 设置转发目标：provider.base-url + 请求路径
        String path = exchange.getRequest().getPath().value();
        URI targetUri = URI.create(target.getBaseUrl() + path);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);
        log.debug("Route selected: provider={}, target={}", provider, targetUri);

        // 转发，完成后记录延迟（供最小延迟策略统计）+ 监控指标 + 调用日志发 MQ
        long start = System.nanoTime();
        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    leastLatencyRouter.recordLatency(provider, latencyMs);
                    recordMetrics(exchange, provider, request, latencyMs);
                    sendCallLog(exchange, provider, latencyMs);
                    log.debug("Route latency: provider={}, {}ms", provider, latencyMs);
                });
    }

    /** 埋点：请求计数 + 延迟 + token 消耗 */
    private void recordMetrics(ServerWebExchange exchange, String provider,
                               LlmRequest request, long latencyMs) {
        try {
            HttpStatus status = resolveStatus(exchange);
            String statusName = status != null ? status.name() : "unknown";
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

    /** 构建调用日志（fire-and-forget 发 MQ，失败不影响主流程） */
    private void sendCallLog(ServerWebExchange exchange, String provider, long latencyMs) {
        try {
            CallLog callLog = new CallLog();
            callLog.setRequestId(exchange.getAttribute("requestId"));
            callLog.setAppKey(exchange.getAttribute("appKey"));
            callLog.setProvider(provider);
            LlmRequest request = exchange.getAttribute("llmRequest");
            if (request != null) {
                callLog.setModel(request.getModel());
            }
            Integer totalTokens = exchange.getAttribute("llmTotalTokens");
            callLog.setTotalTokens(totalTokens != null ? totalTokens : 0);
            callLog.setLatencyMs((int) latencyMs);

            HttpStatus status = resolveStatus(exchange);
            boolean success = status != null && status.is2xxSuccessful();
            callLog.setStatus(success ? "success" : "fail");
            callLog.setErrorMessage(success ? null : String.valueOf(status));
            callLog.setCreatedAt(LocalDateTime.now());

            logProducer.sendLog(callLog);
        } catch (Exception e) {
            log.warn("Failed to build call log: {}", e.getMessage());
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
                .filter(p -> p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .toList();
    }

    private String resolveStrategy(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("X-Router-Strategy");
        return header != null ? header : llmProperties.getRouter().getStrategy();
    }

    /** 熔断降级响应：带 X-Circuit-Breaker: OPEN 标记 */
    private Mono<Void> writeFallbackResponse(ServerWebExchange exchange, LlmResponse response) {
        ServerHttpResponse httpResponse = exchange.getResponse();
        httpResponse.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        httpResponse.getHeaders().add("X-Circuit-Breaker", "OPEN");
        byte[] body;
        try {
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
        return Ordered.HIGHEST_PRECEDENCE + 55;
    }
}
