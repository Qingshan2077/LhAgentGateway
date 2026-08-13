package com.lh.gateway.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lh.gateway.cache.CacheKeyGenerator;
import com.lh.gateway.cache.MultiLevelCacheManager;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import com.lh.gateway.monitor.CustomMetrics;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
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
 * <p>流程：读取请求上下文 → 选择 Provider → 生成缓存 Key → 查缓存
 * （本地 Caffeine → Redis Bitmap 布隆过滤器 → Redis 数据），
 * 命中直接返回；未命中转发上游，并在响应完成后把结果写回缓存（布隆 + Redis + 本地）。</p>
 *
 * <p>熔断结果统计由独立的 {@link CircuitBreakerResultFilter} 完成，避免熔断能力依赖缓存开关。</p>
 *
 * <p>只缓存非流式请求（stream=false）的 2xx 响应。缓存命中响应带 {@code X-Cache: HIT} 头，
 * 熔断降级响应带 {@code X-Circuit-Breaker: OPEN} 头。</p>
 *
 * <p>配置：{@code llm.cache.enabled}（默认 true，A/B 压测可关闭）、
 * {@code llm.cache.ttl-seconds}（默认 300）。</p>
 *
 * <p>order = +50，位于请求上下文与路由选择之后、上游转发之前。</p>
 */
@Slf4j
@Component
public class CacheFilter implements GlobalFilter, Ordered {

    private static final String CACHE_KEY_ATTR = "cacheKey";

    private final MultiLevelCacheManager cacheManager;
    private final CacheKeyGenerator keyGenerator;
    private final CustomMetrics customMetrics;
    private final ObjectMapper objectMapper;

    @Value("${llm.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${llm.cache.ttl-seconds:300}")
    private long cacheTtlSeconds;

    public CacheFilter(MultiLevelCacheManager cacheManager,
                       CacheKeyGenerator keyGenerator,
                       CustomMetrics customMetrics,
                       ObjectMapper objectMapper) {
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
        this.customMetrics = customMetrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 请求体解析与重建由 LlmRequestContextFilter 统一完成；本过滤器只决定是否缓存。
        if (!cacheEnabled
                || !"POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                || !exchange.getRequest().getURI().getPath().contains("/v1/chat/completions")) {
            return chain.filter(exchange);
        }

        LlmRequest request = exchange.getAttribute(LlmRequestContextFilter.LLM_REQUEST_ATTR);
        String body = exchange.getAttribute(LlmRequestContextFilter.REQUEST_BODY_TEXT_ATTR);
        String selectedProvider = exchange.getAttribute(RouterFilter.PROVIDER_ATTR);
        if (request == null || body == null || selectedProvider == null || selectedProvider.isBlank()
                || Boolean.TRUE.equals(request.getStream())) {
            return chain.filter(exchange);
        }

        // Provider 已由前置 RouterFilter 唯一选定，缓存不再承担任何路由职责。
        String provider = selectedProvider.trim().toLowerCase(Locale.ROOT);
        return lookupOrForward(exchange, chain, body, provider);
    }

    private Mono<Void> lookupOrForward(ServerWebExchange exchange,
                                       GatewayFilterChain chain,
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
                    return forwardAndCache(exchange, chain, cacheKey);
                }));
    }

    /**
     * 转发上游，并用响应装饰器截获响应体：
     * 2xx 时异步写缓存。
     * 缓存写失败不影响主响应（异步订阅 + 仅告警日志）。
     */
    private Mono<Void> forwardAndCache(ServerWebExchange exchange, GatewayFilterChain chain,
                                       String cacheKey) {
        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                Flux<DataBuffer> flux = Flux.from(body);
                return super.writeWith(flux.collectList().flatMap(dataBuffers -> {
                    byte[] responseBytes = mergeBuffers(dataBuffers);
                    if (getStatusCode() != null) {
                        boolean success = getStatusCode().is2xxSuccessful();
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
        return chain.filter(exchange.mutate().response(decorator).build());
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
            if (response.getUsage() != null) {
                if (response.getUsage().getPromptTokens() != null) {
                    exchange.getAttributes().put("llmPromptTokens", response.getUsage().getPromptTokens());
                }
                if (response.getUsage().getCompletionTokens() != null) {
                    exchange.getAttributes().put("llmCompletionTokens", response.getUsage().getCompletionTokens());
                }
                if (response.getUsage().getTotalTokens() != null) {
                    exchange.getAttributes().put("llmTotalTokens", response.getUsage().getTotalTokens());
                }
            }
        } catch (Exception e) {
            log.debug("Skip usage extraction: {}", e.getMessage());
        }
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
        storeUsage(exchange, body);
        DataBuffer buffer = httpResponse.bufferFactory().wrap(body);
        return httpResponse.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
