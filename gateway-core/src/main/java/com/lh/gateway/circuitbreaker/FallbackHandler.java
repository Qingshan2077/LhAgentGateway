package com.lh.gateway.circuitbreaker;

import com.lh.gateway.adapter.AdapterFactory;
import com.lh.gateway.adapter.LlmAdapter;
import com.lh.gateway.config.LlmProperties;
import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import com.lh.gateway.model.ProviderConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 熔断降级处理器
 *
 * <p>当某个 Provider 熔断时，按配置动态降级：</p>
 * <ol>
 *   <li>从 llm.providers 筛选启用、健康、支持当前模型且非故障源的 Provider</li>
 *   <li>按权重降序逐个尝试，并将源模型映射为候选 Provider 的真实模型</li>
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
    private final ProviderHealthChecker healthChecker;
    private final LlmProperties llmProperties;

    public FallbackHandler(AdapterFactory adapterFactory,
                           CircuitBreakerRegistry circuitBreakerRegistry,
                           ProviderHealthChecker healthChecker,
                           LlmProperties llmProperties) {
        this.adapterFactory = adapterFactory;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.healthChecker = healthChecker;
        this.llmProperties = llmProperties;
    }

    /**
     * 执行降级：按配置筛选并逐个尝试备选 Provider，全部失败返回降级响应
     */
    public Mono<LlmResponse> fallback(String failedProvider, LlmRequest request) {
        List<FallbackCandidate> candidates = fallbackCandidates(failedProvider, request.getModel());
        if (candidates.isEmpty()) {
            log.error("No enabled and healthy fallback provider supports model={}", request.getModel());
            return Mono.just(createDegradedResponse());
        }
        return tryFallback(failedProvider, request, candidates, 0);
    }

    private Mono<LlmResponse> tryFallback(String failedProvider,
                                          LlmRequest request,
                                          List<FallbackCandidate> candidates,
                                          int index) {
        // 所有备选都试过了 → 返回降级响应
        if (index >= candidates.size()) {
            log.error("All providers failed, returning degraded response");
            return Mono.just(createDegradedResponse());
        }

        FallbackCandidate candidate = candidates.get(index);
        String provider = normalize(candidate.config().getName());

        log.warn("Fallback: {} -> {}, model {} -> {}",
                failedProvider, provider, request.getModel(), candidate.targetModel());
        LlmAdapter adapter = adapterFactory.getAdapter(provider);
        // 每个备选 Provider 一个独立的 Resilience4j 熔断器，防止反复打故障供应商
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("fallback-" + provider);
        LlmRequest mappedRequest = copyWithModel(request, candidate.targetModel());

        return Mono.defer(() -> adapter.call(mappedRequest))
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .onErrorResume(e -> {
                    log.warn("Fallback {} also failed: {}", provider, e.getMessage());
                    return tryFallback(failedProvider, request, candidates, index + 1);
                });
    }

    /**
     * 候选来源完全由 llm.providers 决定：启用、非故障源、适配器存在、健康且能映射当前模型。
     * 权重高的 Provider 优先作为备选；相同权重保持配置顺序。
     */
    private List<FallbackCandidate> fallbackCandidates(String failedProvider, String sourceModel) {
        String failed = normalize(failedProvider);
        return llmProperties.getProviders().stream()
                .filter(ProviderConfig::isEnabled)
                .filter(config -> config.getName() != null)
                .filter(config -> !normalize(config.getName()).equals(failed))
                .filter(config -> adapterFactory.supports(config.getName()))
                .filter(config -> healthChecker.isHealthy(normalize(config.getName())))
                .map(config -> new FallbackCandidate(config, resolveTargetModel(config, sourceModel)))
                .filter(candidate -> candidate.targetModel() != null)
                .sorted(Comparator.comparingInt(
                        (FallbackCandidate candidate) -> weight(candidate.config())).reversed())
                .toList();
    }

    /** models 的 key 为源模型，name 为该 Provider 的目标模型；"*" 表示默认映射。 */
    private String resolveTargetModel(ProviderConfig config, String sourceModel) {
        Map<String, ProviderConfig.ModelConfig> models = config.getModels();
        if (models == null || models.isEmpty()) {
            return null;
        }

        ProviderConfig.ModelConfig mapping = sourceModel == null ? null : models.get(sourceModel);
        if (mapping == null) {
            mapping = models.get("*");
        }
        if (mapping != null && mapping.getName() != null && !mapping.getName().isBlank()) {
            return mapping.getName();
        }

        if (sourceModel != null) {
            return models.values().stream()
                    .map(ProviderConfig.ModelConfig::getName)
                    .filter(sourceModel::equals)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private LlmRequest copyWithModel(LlmRequest source, String targetModel) {
        LlmRequest copy = new LlmRequest();
        copy.setModel(targetModel);
        copy.setMessages(source.getMessages());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setUser(source.getUser());
        copy.setTools(source.getTools());
        copy.setToolChoice(source.getToolChoice());
        copy.setStream(false);
        return copy;
    }

    private int weight(ProviderConfig config) {
        return config.getWeight() != null ? config.getWeight() : 1;
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private record FallbackCandidate(ProviderConfig config, String targetModel) {}

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
