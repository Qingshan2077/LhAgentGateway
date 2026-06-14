package com.lh.gateway.circuitbreaker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器工厂 — 管理所有 Provider 的熔断器
 *
 * <p>每个 Provider 有独立的熔断器，互不影响。</p>
 */
@Slf4j
@Component
public class CircuitBreakerFactory {

    private final Map<String, ProviderCircuitBreaker> breakers = new ConcurrentHashMap<>();

    /**
     * 获取或创建 Provider 的熔断器
     */
    public ProviderCircuitBreaker getBreaker(String providerName) {
        return breakers.computeIfAbsent(providerName, name -> {
            log.info("Creating circuit breaker for provider: {}", name);
            return new ProviderCircuitBreaker(name);
        });
    }

    /**
     * 获取所有熔断器状态（用于监控）
     */
    public Map<String, CircuitBreakerState> getAllStates() {
        Map<String, CircuitBreakerState> states = new ConcurrentHashMap<>();
        breakers.forEach((name, breaker) -> states.put(name, breaker.getState()));
        return states;
    }

    /**
     * 重置某个 Provider 的熔断器（管理后台操作）
     */
    public void reset(String providerName) {
        ProviderCircuitBreaker breaker = breakers.get(providerName);
        if (breaker != null) {
            breaker.reset();
            log.info("Circuit breaker reset for provider: {}", providerName);
        }
    }
}
