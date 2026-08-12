package com.lh.gateway.circuitbreaker;

import com.lh.gateway.adapter.AdapterFactory;
import com.lh.gateway.adapter.LlmAdapter;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 熔断降级处理器
 *
 * <p>当某个 Provider 熔断时，按优先级降级：</p>
 * <ol>
 *   <li>备选 Provider（如 OpenAI 熔断 → DeepSeek → Claude），逐个尝试</li>
 *   <li>返回固定降级响应</li>
 * </ol>
 *
 * <p>每个备选 Provider 的调用由 Resilience4j CircuitBreaker 单独保护
 * （自研 {@link ProviderCircuitBreaker} 负责主链路快速熔断，Resilience4j 负责降级调用路径），
 * 避免备选 Provider 本身故障时反复重试拖垮网关。</p>
 */
@Slf4j
@Component
public class FallbackHandler {

    private final AdapterFactory adapterFactory;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** 备选 Provider 优先级列表 */
    private static final String[] FALLBACK_ORDER = {"deepseek", "claude", "openai"};

    public FallbackHandler(AdapterFactory adapterFactory, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.adapterFactory = adapterFactory;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * 执行降级：按优先级逐个尝试备选 Provider，全部失败返回降级响应
     */
    public Mono<LlmResponse> fallback(String failedProvider, LlmRequest request) {
        return tryFallback(failedProvider, request, 0);
    }

    private Mono<LlmResponse> tryFallback(String failedProvider, LlmRequest request, int index) {
        // 所有备选都试过了 → 返回降级响应
        if (index >= FALLBACK_ORDER.length) {
            log.error("All providers failed, returning degraded response");
            return Mono.just(createDegradedResponse());
        }

        String candidate = FALLBACK_ORDER[index];
        if (candidate.equals(failedProvider) || !adapterFactory.supports(candidate)) {
            return tryFallback(failedProvider, request, index + 1);
        }

        log.warn("Fallback: {} → {}", failedProvider, candidate);
        LlmAdapter adapter = adapterFactory.getAdapter(candidate);
        // 每个备选 Provider 一个独立的 Resilience4j 熔断器，防止反复打故障供应商
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("fallback-" + candidate);

        return Mono.defer(() -> adapter.call(request))
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .onErrorResume(e -> {
                    log.warn("Fallback {} also failed: {}", candidate, e.getMessage());
                    return tryFallback(failedProvider, request, index + 1);
                });
    }

    private LlmResponse createDegradedResponse() {
        LlmResponse response = new LlmResponse();
        response.setId("degraded");
        response.setObject("chat.completion");
        response.setModel("fallback");
        LlmResponse.Choice choice = new LlmResponse.Choice();
        LlmRequest.Message msg = new LlmRequest.Message();
        msg.setRole("assistant");
        msg.setContent("服务暂不可用，请稍后重试。");
        choice.setMessage(msg);
        response.setChoices(java.util.List.of(choice));
        return response;
    }
}
