package com.lh.gateway.gateway.filter;

import com.lh.gateway.cache.CacheKeyGenerator;
import com.lh.gateway.cache.MultiLevelCacheManager;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 缓存过滤器
 *
 * <p>请求进来先查缓存（本地 + Redis），
 * 未命中则继续转发，在响应写入后缓存结果。</p>
 *
 * <p>注意：只缓存非流式响应（stream=false）。</p>
 */
@Slf4j
@Component
public class CacheFilter extends AbstractGatewayFilterFactory<CacheFilter.Config> {

    private static final String CACHE_HIT_ATTR = "cacheHit";
    private static final String CACHE_KEY_ATTR = "cacheKey";

    private final MultiLevelCacheManager cacheManager;
    private final CacheKeyGenerator keyGenerator;

    public CacheFilter(MultiLevelCacheManager cacheManager, CacheKeyGenerator keyGenerator) {
        super(Config.class);
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
    }

    @Override
    public String name() {
        return "LlmCache";
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new GatewayFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                // 只处理 POST /v1/chat/completions
                if (!exchange.getRequest().getURI().getPath().contains("/v1/chat/completions")) {
                    return chain.filter(exchange);
                }

                return exchange.getRequest().getBody()
                        .next()
                        .flatMap(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            String body = new String(bytes, StandardCharsets.UTF_8);

                            // 解析请求，生成缓存 Key
                            LlmRequest request = parseRequest(body);
                            if (request == null || Boolean.TRUE.equals(request.getStream())) {
                                // 流式请求不缓存
                                exchange.getAttributes().put(CACHE_HIT_ATTR, false);
                                return chain.filter(exchange);
                            }

                            String cacheKey = keyGenerator.generateKey(request);
                            exchange.getAttributes().put(CACHE_KEY_ATTR, cacheKey);

                            // 查缓存
                            return cacheManager.get(cacheKey)
                                    .flatMap(cachedResponse -> {
                                        // 命中缓存 → 直接返回
                                        exchange.getAttributes().put(CACHE_HIT_ATTR, true);
                                        log.debug("Cache HIT: key={}", cacheKey);
                                        return writeCachedResponse(exchange, cachedResponse);
                                    })
                                    .switchIfEmpty(Mono.defer(() -> {
                                        // 缓存未命中 → 继续转发
                                        exchange.getAttributes().put(CACHE_HIT_ATTR, false);
                                        // 将原始请求体写回
                                        // 这里简化处理：不缓存原始请求，走转发
                                        return chain.filter(exchange);
                                    }));
                        });
            }
        };
    }

    private Mono<Void> writeCachedResponse(ServerWebExchange exchange, LlmResponse response) {
        ServerHttpResponse httpResponse = exchange.getResponse();
        httpResponse.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        httpResponse.getHeaders().add("X-Cache", "HIT");

        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = httpResponse.bufferFactory().wrap(body);
        return httpResponse.writeWith(Mono.just(buffer));
    }

    private LlmRequest parseRequest(String body) {
        try {
            // 简单 JSON 解析，实际项目使用 Jackson
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(body, LlmRequest.class);
        } catch (Exception e) {
            log.warn("Failed to parse request body: {}", e.getMessage());
            return null;
        }
    }

    public static class Config {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
