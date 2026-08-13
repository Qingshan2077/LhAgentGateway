package com.lh.gateway.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由配置
 */
@Configuration
public class GatewayRouteConfig {

    @Value("${llm.upstream.uri:https://api.openai.com}")
    private String llmUpstreamUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("llm_proxy", r -> r
                        .path("/v1/**")
                        .uri(llmUpstreamUri))
                .build();
    }
}
