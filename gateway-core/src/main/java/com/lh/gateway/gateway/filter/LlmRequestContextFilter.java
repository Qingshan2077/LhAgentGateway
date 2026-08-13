package com.lh.gateway.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.router.RoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 统一读取并重建 LLM 请求体，为缓存、动态路由、熔断和观测链路提供请求上下文。
 *
 * <p>该过滤器独立于缓存开关，流式请求和缓存关闭场景同样会执行。请求体解析失败时保留
 * 原始字节并继续路由，避免静默绕过 Provider 选择；下游仍能返回原本应有的 4xx。</p>
 */
@Slf4j
@Component
public class LlmRequestContextFilter implements GlobalFilter, Ordered {

    public static final String LLM_REQUEST_ATTR = "llmRequest";
    public static final String REQUEST_BODY_BYTES_ATTR = "llmRequestBodyBytes";
    public static final String REQUEST_BODY_TEXT_ATTR = "llmRequestBodyText";
    public static final String ROUTING_CONTEXT_ATTR = "llmRoutingContext";
    public static final String PARSE_ERROR_ATTR = "llmRequestParseError";

    private static final String LLM_API_PREFIX = "/v1/";

    private final ObjectMapper objectMapper;

    public LlmRequestContextFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isLlmApi(exchange)) {
            return chain.filter(exchange);
        }

        if (!shouldReadJsonBody(exchange)) {
            exchange.getAttributes().put(ROUTING_CONTEXT_ATTR, buildRoutingContext(exchange, null));
            return chain.filter(exchange);
        }

        byte[] cachedBody = exchange.getAttribute(REQUEST_BODY_BYTES_ATTR);
        if (cachedBody != null) {
            return chain.filter(withBody(exchange, cachedBody));
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] body = new byte[buffer.readableByteCount()];
                    buffer.read(body);
                    DataBufferUtils.release(buffer);
                    return body;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> prepareContextAndContinue(exchange, chain, body));
    }

    private Mono<Void> prepareContextAndContinue(ServerWebExchange exchange,
                                                 GatewayFilterChain chain,
                                                 byte[] body) {
        String bodyText = new String(body, StandardCharsets.UTF_8);
        LlmRequest request = parseRequest(exchange, bodyText);
        if (request != null) {
            exchange.getAttributes().put(LLM_REQUEST_ATTR, request);
        }
        exchange.getAttributes().put(REQUEST_BODY_BYTES_ATTR, body);
        exchange.getAttributes().put(REQUEST_BODY_TEXT_ATTR, bodyText);
        exchange.getAttributes().put(ROUTING_CONTEXT_ATTR, buildRoutingContext(exchange, request));
        return chain.filter(withBody(exchange, body));
    }

    private LlmRequest parseRequest(ServerWebExchange exchange, String body) {
        try {
            return objectMapper.readValue(body, LlmRequest.class);
        } catch (Exception error) {
            exchange.getAttributes().put(PARSE_ERROR_ATTR, error.getMessage());
            log.warn("Failed to parse LLM request body, continuing with raw body: {}", error.getMessage());
            return null;
        }
    }

    private RoutingContext buildRoutingContext(ServerWebExchange exchange, LlmRequest request) {
        String sessionId = firstNonBlank(
                exchange.getRequest().getHeaders().getFirst("X-Session-Id"),
                exchange.getRequest().getHeaders().getFirst("X-Conversation-Id"),
                request != null ? request.getUser() : null);
        return new RoutingContext(
                request != null ? request.getModel() : null,
                exchange.getAttribute("appKey"),
                sessionId,
                exchange.getAttribute("requestId"));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isLlmApi(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith(LLM_API_PREFIX);
    }

    private boolean shouldReadJsonBody(ServerWebExchange exchange) {
        String method = exchange.getRequest().getMethod().name();
        boolean mayHaveBody = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
        if (!mayHaveBody) {
            return false;
        }
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        return contentType == null || MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private ServerWebExchange withBody(ServerWebExchange exchange, byte[] body) {
        ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.setContentLength(body.length);
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        return exchange.mutate().request(decoratedRequest).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 45;
    }
}
