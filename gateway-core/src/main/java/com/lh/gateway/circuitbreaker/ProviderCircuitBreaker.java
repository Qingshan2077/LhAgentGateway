package com.lh.gateway.circuitbreaker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider 级别熔断器
 *
 * <p>滑动窗口统计最近 N 次请求的错误率，
 * 超过阈值则熔断，等待超时后半开试探。</p>
 *
 * <p>注意：这不是替代 Resilience4j，而是作为网关自有的轻量熔断器，
 * 用于 Provider 级别的快速熔断判断。
 * 生产环境可配合 Resilience4j 一起使用（作为第二层防护）。</p>
 */
@Slf4j
public class ProviderCircuitBreaker {

    @Getter
    private final String providerName;

    private final AtomicReference<CircuitBreakerState> state =
            new AtomicReference<>(CircuitBreakerState.CLOSED);

    /** 滑动窗口大小 */
    private static final int WINDOW_SIZE = 10;
    /** 熔断阈值 (%) */
    private static final int FAILURE_THRESHOLD = 50;
    /** 熔断持续时间 (ms) */
    private static final long OPEN_TIMEOUT_MS = 30_000;
    /** 半开状态试探请求数 */
    private static final int HALF_OPEN_MAX_CALLS = 3;

    /** 滑动窗口中的请求记录 (1=成功, 0=失败) */
    private final int[] window = new int[WINDOW_SIZE];
    private final AtomicInteger windowIndex = new AtomicInteger(0);
    private final AtomicInteger windowCount = new AtomicInteger(0);

    /** 熔断开始时间 */
    private final AtomicLong openedAt = new AtomicLong(0);
    /** 半开状态已放行的试探请求数 */
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
    /** 半开状态试探成功数 */
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

    public ProviderCircuitBreaker(String providerName) {
        this.providerName = providerName;
    }

    /**
     * 判断当前请求是否允许通过
     */
    public boolean isCallAllowed() {
        CircuitBreakerState currentState = state.get();
        return switch (currentState) {
            case CLOSED -> true;
            case OPEN -> {
                // 检查是否到达半开恢复时间
                if (System.currentTimeMillis() - openedAt.get() >= OPEN_TIMEOUT_MS) {
                    if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                        halfOpenCalls.set(0);
                        halfOpenSuccesses.set(0);
                        log.info("Circuit breaker {} transitions to HALF_OPEN", providerName);
                        yield true;
                    }
                }
                yield false;
            }
            case HALF_OPEN -> {
                // 半开状态最多允许 3 个试探请求
                if (halfOpenCalls.incrementAndGet() <= HALF_OPEN_MAX_CALLS) {
                    yield true;
                }
                yield false;
            }
        };
    }

    /**
     * 记录调用结果
     */
    public void recordResult(boolean success) {
        if (success) {
            onSuccess();
        } else {
            onFailure();
        }
    }

    private void onSuccess() {
        int idx = windowIndex.getAndUpdate(i -> (i + 1) % WINDOW_SIZE);
        window[idx] = 1;
        windowCount.incrementAndGet();

        // 半开状态下试探成功
        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            int successes = halfOpenSuccesses.incrementAndGet();
            // 连续试探成功 → 恢复 CLOSED
            if (successes >= HALF_OPEN_MAX_CALLS) {
                if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                    log.info("Circuit breaker {} recovered to CLOSED", providerName);
                    reset();
                }
            }
        }
    }

    private void onFailure() {
        int idx = windowIndex.getAndUpdate(i -> (i + 1) % WINDOW_SIZE);
        window[idx] = 0;
        windowCount.incrementAndGet();

        CircuitBreakerState currentState = state.get();
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开试探失败 → 立即回到 OPEN
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                log.warn("Circuit breaker {} transitions to OPEN (half-open probe failed)", providerName);
            }
            return;
        }

        // CLOSED 状态下检查错误率
        if (currentState == CircuitBreakerState.CLOSED) {
            double failureRate = getFailureRate();
            if (failureRate >= FAILURE_THRESHOLD && windowCount.get() >= WINDOW_SIZE) {
                if (state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
                    openedAt.set(System.currentTimeMillis());
                    log.warn("Circuit breaker {} OPEN: failureRate={}%", providerName, failureRate);
                }
            }
        }
    }

    /**
     * 计算滑动窗口错误率
     */
    public double getFailureRate() {
        int count = Math.min(windowCount.get(), WINDOW_SIZE);
        if (count == 0) return 0;
        int failures = 0;
        for (int i = 0; i < count; i++) {
            if (window[i] == 0) failures++;
        }
        return (double) failures / count * 100;
    }

    public CircuitBreakerState getState() {
        return state.get();
    }

    public boolean isOpen() {
        return state.get() == CircuitBreakerState.OPEN;
    }

    public void reset() {
        state.set(CircuitBreakerState.CLOSED);
        windowIndex.set(0);
        windowCount.set(0);
        openedAt.set(0);
        halfOpenCalls.set(0);
        halfOpenSuccesses.set(0);
    }
}
