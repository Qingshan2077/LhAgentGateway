package com.lh.gateway.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 自定义监控指标
 *
 * <p>记录请求计数、延迟、限流次数、缓存命中率等关键指标，
 * 通过 Prometheus 暴露。Grafana 可配置看板展示。</p>
 */
@Slf4j
@Component
public class CustomMetrics {

    private final MeterRegistry meterRegistry;

    /** 请求计数器：tags = {provider, model, status} */
    private final Map<String, Counter> requestCounters = new ConcurrentHashMap<>();
    /** 限流计数器：tags = {level} */
    private final Map<String, Counter> rateLimitCounters = new ConcurrentHashMap<>();
    /** 延迟计时器：tags = {provider} */
    private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    public CustomMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("Custom metrics initialized");
    }

    /**
     * 记录一次请求
     */
    public void recordRequest(String provider, String model, String status) {
        String key = provider + "|" + model + "|" + status;
        requestCounters.computeIfAbsent(key, k ->
                Counter.builder("agent_gateway_requests_total")
                        .tag("provider", provider)
                        .tag("model", model)
                        .tag("status", status)
                        .description("Total LLM requests")
                        .register(meterRegistry)
        ).increment();
    }

    /**
     * 记录一次限流
     */
    public void recordRateLimit(String level) {
        rateLimitCounters.computeIfAbsent(level, k ->
                Counter.builder("agent_gateway_rate_limit_hits_total")
                        .tag("level", level)
                        .description("Total rate limit hits")
                        .register(meterRegistry)
        ).increment();
    }

    /**
     * 记录调用延迟
     */
    public void recordLatency(String provider, long latencyMs) {
        String key = provider;
        latencyTimers.computeIfAbsent(key, k ->
                Timer.builder("agent_gateway_request_duration_ms")
                        .tag("provider", provider)
                        .description("Request latency by provider")
                        .register(meterRegistry)
        ).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录缓存命中率（Gauge 类型）
     */
    public void recordCacheHitRatio(double hitRatio) {
        meterRegistry.gauge("agent_gateway_cache_hit_ratio", hitRatio);
    }

    /**
     * 记录 Token 消耗
     */
    public void recordTokenUsage(String provider, int tokens) {
        Counter.builder("agent_gateway_token_usage_total")
                .tag("provider", provider)
                .description("Total token usage")
                .register(meterRegistry)
                .increment(tokens);
    }
}
