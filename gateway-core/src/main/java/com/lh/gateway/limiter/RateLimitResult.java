package com.lh.gateway.limiter;

/**
 * 限流结果
 */
public class RateLimitResult {

    private final boolean allowed;
    private final long retryAfterMs;

    public static final RateLimitResult ALLOWED = new RateLimitResult(true, 0);

    public static RateLimitResult denied(long retryAfterMs) {
        return new RateLimitResult(false, retryAfterMs);
    }

    public RateLimitResult(boolean allowed, long retryAfterMs) {
        this.allowed = allowed;
        this.retryAfterMs = retryAfterMs;
    }

    public boolean isAllowed() { return allowed; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
