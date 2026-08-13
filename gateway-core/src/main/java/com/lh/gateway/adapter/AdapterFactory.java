package com.lh.gateway.adapter;

import com.lh.gateway.config.LlmProperties;
import com.lh.gateway.model.ProviderConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

/**
 * 适配器工厂
 *
 * <p>统一管理 OpenAI / DeepSeek / Claude 三个供应商的适配器。
 * 适配器用于熔断降级时的备选 Provider 调用与 30s 健康检查轮询；
 * 主转发链路走 Spring Cloud Gateway 路由直转。</p>
 *
 * <p>适配器直接使用 {@code llm.providers} 中的 base-url 与 api-key，
 * 保证主路由配置和降级调用配置来源一致。</p>
 */
@Component
public class AdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(AdapterFactory.class);

    private volatile Map<String, LlmAdapter> adapterMap = Map.of();
    private final WebClient.Builder webClientBuilder;
    private final LlmProperties llmProperties;

    public AdapterFactory(WebClient.Builder webClientBuilder, LlmProperties llmProperties) {
        this.webClientBuilder = webClientBuilder;
        this.llmProperties = llmProperties;
    }

    @PostConstruct
    public void initDefaultAdapters() {
        reloadAdapters(llmProperties.getProviders());
    }

    /** 配置热更新时原子刷新可用 Adapter 集合。 */
    public synchronized void reloadAdapters(java.util.List<ProviderConfig> providers) {
        Map<String, LlmAdapter> refreshed = new HashMap<>();
        providers.stream()
                .filter(ProviderConfig::isEnabled)
                .filter(config -> config.getName() != null && config.getBaseUrl() != null)
                .forEach(config -> registerConfiguredAdapter(config, refreshed));
        adapterMap = Map.copyOf(refreshed);
        log.info("Registered LLM fallback adapters: {}", adapterMap.keySet());
    }

    private void registerConfiguredAdapter(ProviderConfig config, Map<String, LlmAdapter> targetMap) {
        String provider = normalize(config.getName());
        WebClient webClient = webClientBuilder.clone().baseUrl(config.getBaseUrl()).build();
        LlmAdapter adapter = switch (provider) {
            case "openai" -> new OpenAIAdapter(webClient, config.getApiKey());
            case "deepseek" -> new DeepSeekAdapter(webClient, config.getApiKey());
            case "claude" -> new ClaudeAdapter(webClient, config.getApiKey());
            default -> null;
        };
        if (adapter == null) {
            log.warn("No fallback adapter implementation for provider: {}", provider);
            return;
        }
        targetMap.put(adapter.providerName(), adapter);
    }

    public synchronized void register(LlmAdapter adapter) {
        Map<String, LlmAdapter> refreshed = new HashMap<>(adapterMap);
        refreshed.put(adapter.providerName(), adapter);
        adapterMap = Map.copyOf(refreshed);
    }

    public LlmAdapter getAdapter(String providerName) {
        LlmAdapter adapter = adapterMap.get(normalize(providerName));
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported provider: " + providerName);
        }
        return adapter;
    }

    public boolean supports(String providerName) {
        return providerName != null && adapterMap.containsKey(normalize(providerName));
    }

    public Map<String, LlmAdapter> getAllAdapters() {
        return Map.copyOf(adapterMap);
    }

    private String normalize(String providerName) {
        return providerName.trim().toLowerCase(Locale.ROOT);
    }
}
