package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由策略工厂
 *
 * <p>策略模式：通过策略名称获取对应的路由实现。</p>
 */
@Slf4j
@Component
public class RouterFactory {

    private final Map<String, RouterStrategy> strategyMap = new ConcurrentHashMap<>();

    private final WeightedRoundRobinRouter weightedRoundRobin;
    private final LeastLatencyRouter leastLatency;
    private final ConsistentHashRouter consistentHash;

    public RouterFactory(WeightedRoundRobinRouter weightedRoundRobin,
                         LeastLatencyRouter leastLatency,
                         ConsistentHashRouter consistentHash) {
        this.weightedRoundRobin = weightedRoundRobin;
        this.leastLatency = leastLatency;
        this.consistentHash = consistentHash;
    }

    @PostConstruct
    public void init() {
        register("weighted-round-robin", weightedRoundRobin);
        register("least-latency", leastLatency);
        register("consistent-hash", consistentHash);
        log.info("Registered routing strategies: weighted-round-robin, least-latency, consistent-hash");
    }

    public void register(String name, RouterStrategy strategy) {
        strategyMap.put(name, strategy);
    }

    /**
     * 根据策略名获取路由策略实现
     */
    public RouterStrategy getStrategy(String name) {
        RouterStrategy strategy = strategyMap.get(name);
        if (strategy == null) {
            log.warn("Unknown routing strategy '{}', defaulting to weighted-round-robin", name);
            return weightedRoundRobin;
        }
        return strategy;
    }

    public Map<String, RouterStrategy> getAllStrategies() {
        return Map.copyOf(strategyMap);
    }
}
