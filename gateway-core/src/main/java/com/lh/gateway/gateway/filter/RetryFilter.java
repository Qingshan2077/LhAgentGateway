package com.lh.gateway.gateway.filter;

import com.lh.gateway.retry.RetryStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重试过滤器
 *
 * <p>对可重试错误（429/5xx/超时）自动重试，采用指数退避策略。
 * 重试超出阈值后，返回上游处理。</p>
 */
@Slf4j
@Component
public class RetryFilter implements GlobalFilter, Ordered {

    private final RetryStrategy retryStrategy = RetryStrategy.defaultStrategy();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        AtomicInteger attempt = new AtomicInteger(0);

        return attemptRequest(exchange, chain, attempt);
    }

    private Mono<Void> attemptRequest(ServerWebExchange exchange, GatewayFilterChain chain,
                                       AtomicInteger attempt) {
        int currentAttempt = attempt.incrementAndGet();

        return chain.filter(exchange)
                .then(Mono.defer(() -> {
                    // 检查响应状态码
                    HttpStatus status = HttpStatus.resolve(
                            exchange.getResponse().getStatusCode() != null ?
                                    exchange.getResponse().getStatusCode().value() : 0);

                    if (status != null && isRetryable(status) && currentAttempt <= retryStrategy.getMaxAttempts()) {
                        long delay = retryStrategy.computeBackoff(currentAttempt);
                        log.warn("Retry attempt {}/{} after {}ms, status={}",
                                currentAttempt, retryStrategy.getMaxAttempts(), delay, status);

                        return Mono.delay(java.time.Duration.ofMillis(delay))
                                .then(attemptRequest(exchange, chain, attempt));
                    }

                    return Mono.empty();
                }));
    }

    private boolean isRetryable(HttpStatus status) {
        if (status == null) return false;
        return status == HttpStatus.TOO_MANY_REQUESTS
                || status.is5xxServerError();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 40;
    }
}
