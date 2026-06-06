package com.lh.gateway.circuitbreaker;

/**
 * 熔断器状态
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
