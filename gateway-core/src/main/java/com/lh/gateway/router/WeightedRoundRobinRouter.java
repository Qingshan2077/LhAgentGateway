package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 加权轮询路由策略
 *
 * <p>按权重比例分配请求到不同的 Provider。
 * 权重高的 Provider 获得更多流量。</p>
 */
@Slf4j
@Component
public class WeightedRoundRobinRouter implements RouterStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Mono<String> select(List<ProviderConfig> providers, RoutingContext context) {
        if (providers == null || providers.isEmpty()) {
            return Mono.error(new IllegalStateException("No available providers"));
        }

        int totalWeight = providers.stream()
                .mapToInt(p -> p.getWeight() != null ? p.getWeight() : 1)
                .sum();

        int index = counter.getAndIncrement() % totalWeight;

        for (ProviderConfig provider : providers) {
            int weight = provider.getWeight() != null ? provider.getWeight() : 1;
            index -= weight;
            if (index < 0) {
                log.debug("WeightedRoundRobin selected: {} (weight={})", provider.getName(), weight);
                return Mono.just(provider.getName());
            }
        }

        // fallback
        return Mono.just(providers.get(0).getName());
    }
}
