package com.lh.gateway.gateway.filter;

import com.lh.gateway.retry.RetryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RetryFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RetryFilter.class);
    private final RetryStrategy strategy = RetryStrategy.defaultStrategy();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        AtomicInteger attempt = new AtomicInteger(0);
        return attemptRequest(exchange, chain, attempt);
    }

    private Mono<Void> attemptRequest(ServerWebExchange exchange, GatewayFilterChain chain,
                                       AtomicInteger attempt) {
        int current = attempt.incrementAndGet();
        return chain.filter(exchange).then(Mono.defer(() -> {
            HttpStatus status = HttpStatus.resolve(
                    exchange.getResponse().getStatusCode() != null ?
                            exchange.getResponse().getStatusCode().value() : 0);
            if (status != null && isRetryable(status) && current <= strategy.getMaxAttempts()) {
                long delay = strategy.computeBackoff(current);
                log.warn("Retry {}/{} after {}ms", current, strategy.getMaxAttempts(), delay);
                return Mono.delay(Duration.ofMillis(delay))
                        .then(attemptRequest(exchange, chain, attempt));
            }
            return Mono.empty();
        }));
    }

    private boolean isRetryable(HttpStatus status) {
        return status == HttpStatus.TOO_MANY_REQUESTS || status.is5xxServerError();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 40;
    }
}
