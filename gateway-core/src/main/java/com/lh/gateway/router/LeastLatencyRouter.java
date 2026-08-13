package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 最小延迟路由策略
 *
 * <p>实时测量每个 Provider 的历史延迟（滑动窗口滑动平均），
 * 选择最近平均延迟最低的 Provider 处理请求。</p>
 */
@Slf4j
@Component
public class LeastLatencyRouter implements RouterStrategy {

    /** Provider → 延迟记录队列 */
    private final Map<String, ConcurrentLinkedDeque<Long>> latencyRecords = new ConcurrentHashMap<>();

    /** 滑动窗口大小 */
    private static final int WINDOW_SIZE = 10;

    @Override
    public Mono<String> select(List<ProviderConfig> providers, RoutingContext context) {
        if (providers == null || providers.isEmpty()) {
            return Mono.error(new IllegalStateException("No available providers"));
        }

        String selected = providers.get(0).getName();
        double minAvgLatency = Double.MAX_VALUE;

        for (ProviderConfig provider : providers) {
            double avgLatency = getAverageLatency(provider.getName());
            if (avgLatency < minAvgLatency) {
                minAvgLatency = avgLatency;
                selected = provider.getName();
            }
        }

        log.debug("LeastLatency selected: {} (avgLatency={}ms)", selected, minAvgLatency);
        return Mono.just(selected);
    }

    /**
     * 记录一次调用的延迟
     */
    public void recordLatency(String providerName, long latencyMs) {
        var records = latencyRecords.computeIfAbsent(providerName, k -> new ConcurrentLinkedDeque<>());
        records.addLast(latencyMs);
        if (records.size() > WINDOW_SIZE) {
            records.pollFirst();
        }
    }

    private double getAverageLatency(String providerName) {
        var records = latencyRecords.get(providerName);
        if (records == null || records.isEmpty()) {
            return 0;
        }
        return records.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);
    }
}
