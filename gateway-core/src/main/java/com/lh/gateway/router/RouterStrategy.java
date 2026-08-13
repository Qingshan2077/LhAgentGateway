package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 路由策略接口
 * <p>
 * 从可用 Provider 列表中选出一个处理当前请求。
 * 实现策略：加权轮询、最小延迟、一致性哈希。
 * </p>
 */
public interface RouterStrategy {

    /**
     * @param providers 当前可用 Provider 列表
     * @param context   路由上下文（模型、租户、会话和请求标识）
     * @return 选中的 Provider 名称
     */
    Mono<String> select(List<ProviderConfig> providers, RoutingContext context);
}
