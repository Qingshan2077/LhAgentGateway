package com.lh.gateway.circuitbreaker;

import com.lh.gateway.adapter.AdapterFactory;
import com.lh.gateway.adapter.LlmAdapter;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 熔断降级处理器
 *
 * <p>当某个 Provider 熔断时，按优先级降级：
 * <ol>
 *   <li>备选 Provider（如 OpenAI 熔断 → DeepSeek）</li>
 *   <li>返回过时的缓存结果（如果有）</li>
 *   <li>返回固定降级响应</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackHandler {

    private final AdapterFactory adapterFactory;

    /** 备选 Provider 优先级列表 */
    private static final String[] FALLBACK_ORDER = {"deepseek", "claude", "openai"};

    /**
     * 执行降级：尝试备选 Provider
     */
    public Mono<LlmResponse> fallback(String failedProvider, LlmRequest request) {
        for (String fallbackProvider : FALLBACK_ORDER) {
            if (fallbackProvider.equals(failedProvider)) continue;
            if (!adapterFactory.supports(fallbackProvider)) continue;

            log.warn("Fallback: {} → {}", failedProvider, fallbackProvider);
            LlmAdapter adapter = adapterFactory.getAdapter(fallbackProvider);
            return adapter.call(request)
                    .onErrorResume(e -> {
                        log.warn("Fallback {} also failed: {}", fallbackProvider, e.getMessage());
                        return Mono.empty();
                    });
        }

        // 所有备选都不可用 → 返回降级响应
        log.error("All providers failed, returning degraded response");
        return Mono.just(createDegradedResponse());
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
