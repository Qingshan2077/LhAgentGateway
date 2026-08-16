package com.lh.gateway.gateway.filter;

import com.lh.gateway.model.CallLog;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.mq.LogProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 覆盖完整请求生命周期的调用审计日志。
 *
 * <p>位于认证、限流、缓存、路由和上游调用之前，因此正常响应、缓存命中、认证失败、
 * 限流、熔断降级以及无 HTTP 状态码的连接异常都会产生且只产生一条日志。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "llm.call-log", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CallLogFilter implements GlobalFilter, Ordered {

    private final LogProducer logProducer;

    @Value("${llm.upstream.provider:openai}")
    private String defaultProvider;

    public CallLogFilter(LogProducer logProducer) {
        this.logProducer = logProducer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        long startedAt = System.nanoTime();
        LocalDateTime createdAt = LocalDateTime.now();
        AtomicBoolean emitted = new AtomicBoolean(false);
        return chain.filter(exchange)
                .doOnSuccess(ignored -> emitOnce(exchange, startedAt, createdAt, null, emitted))
                .doOnError(error -> emitOnce(exchange, startedAt, createdAt, error, emitted))
                .doOnCancel(() -> emitOnce(exchange, startedAt, createdAt,
                        new IllegalStateException("client_cancelled"), emitted));
    }

    private void emitOnce(ServerWebExchange exchange,
                          long startedAt,
                          LocalDateTime createdAt,
                          Throwable error,
                          AtomicBoolean emitted) {
        if (!emitted.compareAndSet(false, true)) {
            return;
        }

        try {
            CallLog callLog = new CallLog();
            callLog.setRequestId(exchange.getAttribute("requestId"));
            String appKey = exchange.getAttribute("appKey");
            if (appKey == null) {
                appKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
            }
            callLog.setAppKey(appKey);
            callLog.setProvider(resolveProvider(exchange));

            LlmRequest request = exchange.getAttribute(LlmRequestContextFilter.LLM_REQUEST_ATTR);
            callLog.setModel(request != null && request.getModel() != null
                    ? request.getModel() : "unknown");
            callLog.setPromptTokens(valueOrZero(exchange.getAttribute("llmPromptTokens")));
            callLog.setCompletionTokens(valueOrZero(exchange.getAttribute("llmCompletionTokens")));
            callLog.setTotalTokens(valueOrZero(exchange.getAttribute("llmTotalTokens")));
            callLog.setLatencyMs((int) Math.min(Integer.MAX_VALUE,
                    (System.nanoTime() - startedAt) / 1_000_000));

            HttpStatusCode status = exchange.getResponse().getStatusCode();
            callLog.setStatus(resolveStatus(exchange, status, error));
            callLog.setErrorMessage(resolveErrorMessage(status, error));
            callLog.setCreatedAt(createdAt);
            logProducer.sendLog(callLog);
        } catch (Exception buildError) {
            log.warn("Failed to build asynchronous call log: {}", buildError.getMessage());
        }
    }

    private String resolveProvider(ServerWebExchange exchange) {
        String selected = exchange.getAttribute(RouterFilter.PROVIDER_ATTR);
        if (hasText(selected)) {
            return selected;
        }
        String requested = exchange.getRequest().getHeaders().getFirst("X-Provider");
        if (hasText(requested)) {
            return requested;
        }
        return hasText(defaultProvider) ? defaultProvider : "unknown";
    }

    private String resolveStatus(ServerWebExchange exchange, HttpStatusCode status, Throwable error) {
        if (error != null) {
            return "client_cancelled".equals(error.getMessage()) ? "cancelled" : "error";
        }
        if ("OPEN".equalsIgnoreCase(exchange.getResponse().getHeaders().getFirst("X-Circuit-Breaker"))) {
            return status != null && status.value() == 503 ? "circuit_open" : "degraded";
        }
        int code = status != null ? status.value() : 200;
        if (code == 401 || code == 403) return "unauthorized";
        if (code == 429) return "limited";
        if (code >= 200 && code < 300) return "success";
        return "fail";
    }

    private String resolveErrorMessage(HttpStatusCode status, Throwable error) {
        String message = error != null
                ? error.getClass().getSimpleName() + ": " + error.getMessage()
                : status != null && status.isError() ? "HTTP " + status.value() : null;
        if (message == null || message.length() <= 512) {
            return message;
        }
        return message.substring(0, 512);
    }

    private int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
