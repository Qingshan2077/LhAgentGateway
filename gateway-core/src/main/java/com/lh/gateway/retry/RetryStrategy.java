package com.lh.gateway.retry;

/**
 * 重试策略配置
 */
public class RetryStrategy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public RetryStrategy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public static RetryStrategy defaultStrategy() {
        return new RetryStrategy(3, 1000, 8000);
    }

    public int getMaxAttempts() { return maxAttempts; }

    /**
     * 指数退避 + 随机抖动
     */
    public long computeBackoff(int attempt) {
        double exponential = Math.pow(2, attempt) * baseDelayMs;
        double jitter = Math.random() * exponential * 0.5;
        return Math.min((long) (exponential + jitter), maxDelayMs);
    }
}
