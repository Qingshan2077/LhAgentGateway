package com.lh.gateway.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求日志过滤器
 * <p>
 * 为每个请求生成唯一 requestId，记录基本调用信息。
 * 在所有过滤器之前执行（最高优先级）。
 * </p>
 */
@Slf4j
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put("requestId", requestId);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(r -> r.header(REQUEST_ID_HEADER, requestId))
                .build();

        log.info("[{}] {} {} from {}",
                requestId,
                mutatedExchange.getRequest().getMethod(),
                mutatedExchange.getRequest().getURI().getPath(),
                mutatedExchange.getRequest().getRemoteAddress());

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
