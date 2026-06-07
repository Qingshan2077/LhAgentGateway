package com.lh.gateway.adapter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 适配器工厂
 */
@Component
public class AdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(AdapterFactory.class);

    private final Map<String, LlmAdapter> adapterMap = new ConcurrentHashMap<>();
    private final WebClient.Builder webClientBuilder;

    public AdapterFactory(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void initDefaultAdapters() {
        register(new OpenAIAdapter(
                webClientBuilder.baseUrl("https://api.openai.com").build()));
        register(new DeepSeekAdapter(
                webClientBuilder.baseUrl("https://api.deepseek.com").build()));
        register(new ClaudeAdapter(
                webClientBuilder.baseUrl("https://api.anthropic.com").build()));
        log.info("Registered default LLM adapters: openai, deepseek, claude");
    }

    public void register(LlmAdapter adapter) {
        adapterMap.put(adapter.providerName(), adapter);
    }

    public LlmAdapter getAdapter(String providerName) {
        LlmAdapter adapter = adapterMap.get(providerName);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported provider: " + providerName);
        }
        return adapter;
    }

    public boolean supports(String providerName) {
        return adapterMap.containsKey(providerName);
    }

    public Map<String, LlmAdapter> getAllAdapters() {
        return Map.copyOf(adapterMap);
    }
}
