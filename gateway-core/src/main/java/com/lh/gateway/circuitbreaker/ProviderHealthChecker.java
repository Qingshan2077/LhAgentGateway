package com.lh.gateway.circuitbreaker;

import com.lh.gateway.adapter.AdapterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 健康检查器
 *
 * <p>定时（30s）检查所有已注册 Provider 的健康状态，
 * 用于熔断恢复判断和管理后台展示。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderHealthChecker {

    private final AdapterFactory adapterFactory;

    /** Provider → 是否健康 */
    private final Map<String, Boolean> healthStatus = new ConcurrentHashMap<>();

    /**
     * 每 30 秒检查一次所有 Provider 的健康状态
     */
    @Scheduled(fixedRate = 30_000)
    public void checkAllProviders() {
        adapterFactory.getAllAdapters().forEach((name, adapter) -> {
            adapter.healthCheck()
                    .subscribe(
                            healthy -> {
                                healthStatus.put(name, healthy);
                                if (healthy) {
                                    log.debug("Health check OK: {}", name);
                                } else {
                                    log.warn("Health check FAILED: {}", name);
                                }
                            },
                            error -> {
                                healthStatus.put(name, false);
                                log.error("Health check error: {}", name, error);
                            }
                    );
        });
    }

    public boolean isHealthy(String providerName) {
        return healthStatus.getOrDefault(providerName, true);
    }

    public Map<String, Boolean> getAllHealthStatus() {
        return Map.copyOf(healthStatus);
    }
}
