package com.lh.gateway.router;

import com.lh.gateway.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 一致性哈希路由策略
 *
 * <p>同一个 Session（由 requestId 或 appKey 决定）始终路由到同一个 Provider。
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
    private final SortedMap<Integer, String> ring = new TreeMap<>();

    /** 上次更新的 Provider 列表（用于检测变化） */
    private volatile List<ProviderConfig> lastProviders = List.of();

    @Override
    public Mono<String> select(List<ProviderConfig> providers, String model) {
        if (providers == null || providers.isEmpty()) {
            return Mono.error(new IllegalStateException("No available providers"));
        }

        // 如果 Provider 列表变了，重建哈希环
        if (!providers.equals(lastProviders)) {
            rebuildRing(providers);
            lastProviders = List.copyOf(providers);
        }

        // 用 model + appKey（如果有）作为路由 Key
        String routeKey = model;
        int hash = hash(routeKey);

        // 找到最近的顺时针节点
        SortedMap<Integer, String> tailMap = ring.tailMap(hash);
        Integer nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        String selected = ring.get(nodeHash);

        log.debug("ConsistentHash selected: {} (hash={})", selected, hash);
        return Mono.just(selected);
    }

    private void rebuildRing(List<ProviderConfig> providers) {
        ring.clear();
        for (ProviderConfig provider : providers) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                String virtualNode = provider.getName() + "#" + i;
                int hash = hash(virtualNode);
                ring.put(hash, provider.getName());
            }
        }
        log.debug("Consistent hash ring rebuilt: {} providers, {} virtual nodes",
                providers.size(), providers.size() * VIRTUAL_NODES);
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
