package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 一致性哈希路由策略
 *
 * <p>同一模型下，相同 AppKey 或 Session 始终路由到同一个 Provider。
 * 适用于保持对话上下文和流式响应缓冲区一致性。</p>
 *
 * <p>每个物理节点有 160 个虚拟节点，Provider 变化时数据迁移最小化。</p>
 */
@Slf4j
@Component
public class ConsistentHashRouter implements RouterStrategy {

    /** 虚拟节点数 */
    private static final int VIRTUAL_NODES = 160;

    /** 哈希环 */
    private volatile NavigableMap<Integer, String> ring = Collections.emptyNavigableMap();

    /** 上次更新的 Provider 列表（用于检测变化） */
    private volatile List<String> lastProviderNames = List.of();

    @Override
    public Mono<String> select(List<ProviderConfig> providers, RoutingContext context) {
        if (providers == null || providers.isEmpty()) {
            return Mono.error(new IllegalStateException("No available providers"));
        }

        ensureRing(providers);

        // 模型 + 租户 + 会话构成稳定 Key；缺少租户/会话时才退化到 requestId。
        String routeKey = context != null
                ? context.consistentHashKey()
                : new RoutingContext(null, null, null, null).consistentHashKey();
        int hash = hash(routeKey);

        // 找到最近的顺时针节点
        NavigableMap<Integer, String> currentRing = ring;
        var entry = currentRing.ceilingEntry(hash);
        String selected = entry != null
                ? entry.getValue()
                : currentRing.firstEntry().getValue();

        log.debug("ConsistentHash selected: {} (hash={})", selected, hash);
        return Mono.just(selected);
    }

    private void ensureRing(List<ProviderConfig> providers) {
        List<String> providerNames = providers.stream().map(ProviderConfig::getName).toList();
        if (providerNames.equals(lastProviderNames)) {
            return;
        }
        synchronized (this) {
            if (providerNames.equals(lastProviderNames)) {
                return;
            }
            ring = rebuildRing(providers);
            lastProviderNames = List.copyOf(providerNames);
        }
    }

    private NavigableMap<Integer, String> rebuildRing(List<ProviderConfig> providers) {
        NavigableMap<Integer, String> newRing = new TreeMap<>();
        for (ProviderConfig provider : providers) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                String virtualNode = provider.getName() + "#" + i;
                int hash = hash(virtualNode);
                newRing.put(hash, provider.getName());
            }
        }
        log.debug("Consistent hash ring rebuilt: {} providers, {} virtual nodes",
                providers.size(), providers.size() * VIRTUAL_NODES);
        return Collections.unmodifiableNavigableMap(newRing);
    }

    private int hash(String key) {
        try {
            var md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getInt();
        } catch (NoSuchAlgorithmException e) {
            return key.hashCode();
        }
    }
}
