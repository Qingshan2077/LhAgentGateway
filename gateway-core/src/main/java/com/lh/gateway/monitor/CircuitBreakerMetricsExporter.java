package com.lh.gateway.monitor;

import com.lh.gateway.circuitbreaker.CircuitBreakerFactory;
import com.lh.gateway.circuitbreaker.ProviderCircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器指标导出器
 *
 * <p>将每个 Provider 的熔断器状态导出为 Prometheus Gauge 指标，
 * 以便在 Grafana 中监控熔断情况。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerMetricsExporter {

    private final CircuitBreakerFactory breakerFactory;
    private final MeterRegistry meterRegistry;

    private final Map<String, Gauge> stateGauges = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册三个 Provider 的熔断状态 Gauge
        registerBreakerGauge("openai");
        registerBreakerGauge("deepseek");
        registerBreakerGauge("claude");
        log.info("Circuit breaker metrics registered");
    }

    private void registerBreakerGauge(String providerName) {
        Gauge.builder("agent_gateway_circuit_breaker_state", () -> {
                    ProviderCircuitBreaker breaker = breakerFactory.getBreaker(providerName);
                    return switch (breaker.getState()) {
                        case CLOSED -> 0;
                        case HALF_OPEN -> 1;
                        case OPEN -> 2;
                    };
                })
                .tag("provider", providerName)
                .description("Circuit breaker state: 0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .register(meterRegistry);

        // 熔断器错误率
        Gauge.builder("agent_gateway_circuit_breaker_failure_rate", () -> {
                    ProviderCircuitBreaker breaker = breakerFactory.getBreaker(providerName);
                    return breaker.getFailureRate();
                })
                .tag("provider", providerName)
                .description("Circuit breaker failure rate (%)")
                .register(meterRegistry);
    }
}
