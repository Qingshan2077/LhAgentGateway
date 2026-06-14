package com.lh.gateway.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 认证过滤器
 *
 * <p>校验请求的 X-Api-Key 头。如果配置了严格模式，缺少有效 Key 返回 401。
 * 开发环境可以通过配置关闭认证。</p>
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    /** 是否开启认证（从配置读取，可由 application.yml 控制） */
    private boolean authEnabled = true;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!authEnabled) {
            return chain.filter(exchange);
        }

        String apiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Authentication failed: missing X-Api-Key header");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // TODO: 从数据库或配置中心校验 ApiKey 有效性
        // 当前版本：任何非空 Key 都放行（生产环境需替换为真实校验逻辑）
        exchange.getAttributes().put("appKey", apiKey);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
