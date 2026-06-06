package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 路由策略接口
 */
public interface RouterStrategy {

    /** 从可用 Provider 列表中选择一个 */
    Mono<String> select(List<ProviderConfig> providers, String model);
}
