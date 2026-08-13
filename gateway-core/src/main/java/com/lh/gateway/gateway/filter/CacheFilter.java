package com.lh.gateway.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lh.gateway.cache.CacheKeyGenerator;
import com.lh.gateway.cache.MultiLevelCacheManager;
import com.lh.gateway.circuitbreaker.CircuitBreakerFactory;
import com.lh.gateway.config.LlmProperties;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import com.lh.gateway.model.ProviderConfig;
import com.lh.gateway.monitor.CustomMetrics;
import com.lh.gateway.router.RouterFactory;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 缓存过滤器（GlobalFilter）
 *
 * <p>流程：读请求体 → 选择 Provider → 生成缓存 Key → 查缓存
 * （本地 Caffeine → Redis Bitmap 布隆过滤器 → Redis 数据），
 * 命中直接返回；未命中转发上游，并在响应完成后把结果写回缓存（布隆 + Redis + 本地）。</p>
 *
 * <p>熔断配合：转发完成后把调用结果回喂熔断器
 * （{@link com.lh.gateway.circuitbreaker.ProviderCircuitBreaker#recordResult}），
 * 驱动滑动窗口错误率统计；熔断拦截与备选 Provider 降级在 {@link RouterFilter} 中完成。</p>
 *
 * <p>只缓存非流式请求（stream=false）的 2xx 响应。缓存命中响应带 {@code X-Cache: HIT} 头，
 * 熔断降级响应带 {@code X-Circuit-Breaker: OPEN} 头。</p>
 *
 * <p>配置：{@code llm.cache.enabled}（默认 true，A/B 压测可关闭）、
 * {@code llm.cache.ttl-seconds}（默认 300）。</p>
 *
 * <p>order = +50，位于 RetryFilter(+40) 之后、路由选择与上游转发之前。</p>
 */
@Slf4j
@Component
public class CacheFilter implements GlobalFilter, Ordered {

    private static final String CACHE_KEY_ATTR = "cacheKey";

    private final MultiLevelCacheManager cacheManager;
    private final CacheKeyGenerator keyGenerator;
    private final CircuitBreakerFactory breakerFactory;
    private final CustomMetrics customMetrics;
    private final LlmProperties llmProperties;
    private final RouterFactory routerFactory;
    private final ObjectMapper objectMapper;

    @Value("${llm.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${llm.cache.ttl-seconds:300}")
    private long cacheTtlSeconds;

    @Value("${llm.upstream.provider:openai}")
    private String defaultProvider;

    public CacheFilter(MultiLevelCacheManager cacheManager,
                       CacheKeyGenerator keyGenerator,
                       CircuitBreakerFactory breakerFactory,
                       CustomMetrics customMetrics,
                       LlmProperties llmProperties,
                       RouterFactory routerFactory,
                       ObjectMapper objectMapper) {
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
        this.breakerFactory = breakerFactory;
        this.customMetrics = customMetrics;
        this.llmProperties = llmProperties;
        this.routerFactory = routerFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 缓存关闭或非 LLM 接口：直接放行
        if (!cacheEnabled
                || !"POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                || !exchange.getRequest().getURI().getPath().contains("/v1/chat/completions")) {
            return chain.filter(exchange);
        }

        // 读取请求体（Reactive 流只能订阅一次，读完必须重建请求，否则下游转发时 body 为空）
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    String body = new String(bytes, StandardCharsets.UTF_8);

                    LlmRequest request = parseRequest(body);
                    // 解析失败或流式请求：不缓存，重建 body 后放行
                    if (request == null || Boolean.TRUE.equals(request.getStream())) {
                        return chain.filter(mutateExchange(exchange, bytes, exchange.getResponse()));
                    }

                    // 供后续过滤器（路由选择/熔断降级/日志）使用
                    exchange.getAttributes().put("llmRequest", request);

                    // 缓存 Key 必须包含本次真实路由 Provider。这里提前选择并写入 attribute，
                    // RouterFilter 在缓存未命中时复用该结果，避免加权轮询被选择两次。
                    return resolveProvider(exchange, request)
                            .flatMap(provider -> lookupOrForward(
                                    exchange, chain, bytes, body, provider));
                });
    }

    private Mono<Void> lookupOrForward(ServerWebExchange exchange,
                                       GatewayFilterChain chain,
                                       byte[] requestBytes,
                                       String requestBody,
                                       String provider) {
        exchange.getAttributes().put(RouterFilter.PROVIDER_ATTR, provider);
        String appKey = exchange.getAttribute("appKey");
        String cacheKey = keyGenerator.generateKey(requestBody, provider, appKey);
        exchange.getAttributes().put(CACHE_KEY_ATTR, cacheKey);

        return cacheManager.get(cacheKey)
                .flatMap(cachedResponseJson -> {
                    log.debug("Cache HIT: key={}, provider={}", cacheKey, provider);
                    customMetrics.recordCacheHit(provider);
                    return writeCachedResponse(exchange, cachedResponseJson);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Cache MISS: key={}, provider={}", cacheKey, provider);
                    customMetrics.recordCacheMiss(provider);
                    return forwardAndCache(exchange, chain, requestBytes, cacheKey);
                }));
    }

    /** 选择缓存命名空间对应的真实 Provider；RouterFilter 会复用选择结果。 */
    private Mono<String> resolveProvider(ServerWebExchange exchange, LlmRequest request) {
        String preselected = exchange.getAttribute(RouterFilter.PROVIDER_ATTR);
        if (hasText(preselected)) {
            return Mono.just(normalizeProvider(preselected));
        }

        List<ProviderConfig> providers = configuredProviders();
        String requested = exchange.getRequest().getHeaders().getFirst("X-Provider");
        if (hasText(requested)) {
            return Mono.just(providers.stream()
                    .filter(p -> p.getName().equalsIgnoreCase(requested.trim()))
                    .map(ProviderConfig::getName)
                    .findFirst()
                    .map(this::normalizeProvider)
                    .orElseGet(this::normalizedDefaultProvider));
        }

        if (providers.isEmpty()) {
            return Mono.just(normalizedDefaultProvider());
        }

        String strategy = exchange.getRequest().getHeaders().getFirst("X-Router-Strategy");
        if (!hasText(strategy)) {
            strategy = llmProperties.getRouter().getStrategy();
        }
        return routerFactory.getStrategy(strategy)
                .select(providers, request.getModel())
                .map(this::normalizeProvider);
    }

    private List<ProviderConfig> configuredProviders() {
        return llmProperties.getProviders().stream()
                .filter(p -> hasText(p.getName()) && hasText(p.getBaseUrl()))
                .toList();
    }

    private String normalizedDefaultProvider() {
        return hasText(defaultProvider) ? normalizeProvider(defaultProvider) : "openai";
    }

    private String normalizeProvider(String provider) {
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 转发上游，并用响应装饰器截获响应体：
     * 2xx 时异步写缓存；调用结果回喂熔断器（驱动滑动窗口错误率统计）。
     * Provider 取自路由选择（RouterFilter 写入的 attribute）或 X-Provider 头。
     * 缓存写失败不影响主响应（异步订阅 + 仅告警日志）。
     */
    private Mono<Void> forwardAndCache(ServerWebExchange exchange, GatewayFilterChain chain,
                                       byte[] requestBody, String cacheKey) {
        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                Flux<DataBuffer> flux = Flux.from(body);
                return super.writeWith(flux.collectList().flatMap(dataBuffers -> {
                    byte[] responseBytes = mergeBuffers(dataBuffers);
                    if (getStatusCode() != null) {
                        boolean success = getStatusCode().is2xxSuccessful();
                        String provider = resolveProvider(exchange);
                        if (provider != null) {
                            breakerFactory.getBreaker(provider).recordResult(success);
                            log.debug("Record result for provider={}, success={}", provider, success);
                        }
                        if (success) {
                            storeUsage(exchange, responseBytes);
                            cacheResponseAsync(cacheKey, responseBytes);
                        } else {
                            log.debug("Skip caching, status={}", getStatusCode());
                        }
                    }
                    return Mono.just(getDelegate().bufferFactory().wrap(responseBytes));
                }));
            }
        };
        return chain.filter(mutateExchange(exchange, requestBody, decorator));
    }

    /** 取当前请求的 Provider：路由选择优先，其次 X-Provider 头 */
    private String resolveProvider(ServerWebExchange exchange) {
        String selected = exchange.getAttribute(RouterFilter.PROVIDER_ATTR);
        if (hasText(selected)) {
            return normalizeProvider(selected);
        }
        String requested = exchange.getRequest().getHeaders().getFirst("X-Provider");
        if (hasText(requested)) {
            return normalizeProvider(requested);
        }
        return normalizedDefaultProvider();
    }

    /** 异步写缓存：验证响应体 → 写入布隆 + Redis + 本地缓存 */
    private void cacheResponseAsync(String cacheKey, byte[] responseBytes) {
        try {
            // 只验证响应是合法 JSON，缓存保存原文，确保 tool_calls/logprobs 等扩展字段不丢失。
            var json = objectMapper.readTree(responseBytes);
            if (json == null || !json.isObject()) {
                log.debug("Skip caching non-object JSON response");
                return;
            }
            String responseJson = new String(responseBytes, StandardCharsets.UTF_8);
            cacheManager.put(cacheKey, responseJson, cacheTtlSeconds)
                    .subscribe(v -> log.debug("Cache PUT: key={}", cacheKey),
                               e -> log.warn("Cache put failed: key={}, err={}", cacheKey, e.getMessage()));
        } catch (Exception e) {
            // 响应体不是合法 JSON（如上游错误页），跳过缓存，不影响主流程
            log.debug("Skip caching unparseable response: {}", e.getMessage());
        }
    }

    /** 提取响应 usage 的 token 总数，存入 attribute 供调用日志（MQ）使用 */
    private void storeUsage(ServerWebExchange exchange, byte[] responseBytes) {
        try {
            LlmResponse response = objectMapper.readValue(responseBytes, LlmResponse.class);
            if (response.getUsage() != null && response.getUsage().getTotalTokens() != null) {
                exchange.getAttributes().put("llmTotalTokens", response.getUsage().getTotalTokens());
            }
        } catch (Exception e) {
            log.debug("Skip usage extraction: {}", e.getMessage());
        }
    }

    /** 重建请求（带上已读取的 body）并替换响应 */
    private ServerWebExchange mutateExchange(ServerWebExchange exchange, byte[] body,
                                             ServerHttpResponse response) {
        ServerHttpRequest newRequest = exchange.getRequest().mutate()
                .body(Flux.just(exchange.getResponse().bufferFactory().wrap(body)))
                .build();
        return exchange.mutate().request(newRequest).response(response).build();
    }

    /** 合并响应 DataBuffer 列表为字节数组（collectList 后所有权已转移，需手动 release） */
    private byte[] mergeBuffers(List<DataBuffer> dataBuffers) {
        int size = 0;
        for (DataBuffer b : dataBuffers) {
            size += b.readableByteCount();
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (DataBuffer b : dataBuffers) {
            int len = b.readableByteCount();
            b.read(result, offset, len);
            offset += len;
            DataBufferUtils.release(b);
        }
        return result;
    }

    /** 命中缓存：原样写回上游 JSON，避免丢失尚未建模的 OpenAI 扩展字段。 */
    private Mono<Void> writeCachedResponse(ServerWebExchange exchange, String responseJson) {
        ServerHttpResponse httpResponse = exchange.getResponse();
        httpResponse.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        httpResponse.getHeaders().add("X-Cache", "HIT");
        byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = httpResponse.bufferFactory().wrap(body);
        return httpResponse.writeWith(Mono.just(buffer));
    }

    private LlmRequest parseRequest(String body) {
        try {
            return objectMapper.readValue(body, LlmRequest.class);
        } catch (Exception e) {
            log.warn("Failed to parse request body: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
