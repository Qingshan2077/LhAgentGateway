package com.lh.gateway.config;

import com.lh.gateway.model.ProviderConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关 llm.* 配置属性
 *
 * <p>绑定 application.yml 中 {@code llm.router}（路由策略）与
 * {@code llm.providers}（多 Provider 列表：name/base-url/weight）。
 * Provider 列表为空时网关走默认路由（llm.upstream.uri）。</p>
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 路由策略配置 */
    private Router router = new Router();

    /** 多 Provider 配置（用于路由策略选择） */
    private volatile List<ProviderConfig> providers = new ArrayList<>();

    public Router getRouter() {
        return router;
    }

    public void setRouter(Router router) {
        this.router = router;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers != null ? List.copyOf(providers) : List.of();
    }

    public static class Router {
        /** 默认路由策略：weighted | least-latency | consistent-hash */
        private String strategy = "weighted";

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }
    }
}
