package com.lh.gateway.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestUrlFilterTest {

    private final ProviderRequestUrlFilter filter = new ProviderRequestUrlFilter();

    @Test
    void runsAfterStaticRouteExpansion() {
        assertThat(filter.getOrder())
                .isGreaterThan(RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER);
    }

    @Test
    void restoresProviderTargetBeforeContinuingChain() {
        URI staticTarget = URI.create("https://api.openai.com/v1/chat/completions");
        URI providerTarget = URI.create("http://localhost:9999/v1/chat/completions");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, staticTarget);
        exchange.getAttributes().put(RouterFilter.PROVIDER_TARGET_URL_ATTR, providerTarget);
        AtomicBoolean invoked = new AtomicBoolean();

        filter.filter(exchange, current -> {
            invoked.set(true);
            assertThat((URI) current.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR))
                    .isEqualTo(providerTarget);
            return Mono.empty();
        }).block();

        assertThat(invoked).isTrue();
    }
}
