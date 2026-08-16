package com.lh.gateway.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Reapplies the dynamic Provider URL after Spring Cloud Gateway has expanded the static route URI.
 *
 * <p>{@link RouterFilter} must stay early in the chain because cache and Provider rate-limit filters
 * consume its selected Provider attribute. {@link RouteToRequestUrlFilter} runs much later (order
 * 10000) and overwrites {@code GATEWAY_REQUEST_URL_ATTR}; this filter runs immediately afterwards
 * and restores the Provider-specific target before Netty performs the upstream request.</p>
 */
@Component
public class ProviderRequestUrlFilter implements GlobalFilter, Ordered {

    static final int ORDER = RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI providerTarget = exchange.getAttribute(RouterFilter.PROVIDER_TARGET_URL_ATTR);
        if (providerTarget != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, providerTarget);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
