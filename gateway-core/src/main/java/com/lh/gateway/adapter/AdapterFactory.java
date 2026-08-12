package com.lh.gateway.adapter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 适配器工厂
 *
 * <p>统一管理 OpenAI / DeepSeek / Claude 三个供应商的适配器。
 * 适配器用于熔断降级时的备选 Provider 调用与 30s 健康检查轮询；
 * 主转发链路走 Spring Cloud Gateway 路由直转。</p>
 *
 * <p>各供应商 base-url 可通过 {@code llm.adapter.*.base-url} 配置
 * （压测验证备选切换时指向本地 Mock 即可）。</p>
 */
@Component
public class AdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(AdapterFactory.class);

    private final Map<String, LlmAdapter> adapterMap = new ConcurrentHashMap<>();
    private final WebClient.Builder webClientBuilder;

    @Value("${llm.adapter.openai.base-url:https://api.openai.com}")
    private String openaiBaseUrl;
    @Value("${llm.adapter.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;
    @Value("${llm.adapter.claude.base-url:https://api.anthropic.com}")
    private String claudeBaseUrl;

    public AdapterFactory(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void initDefaultAdapters() {
        register(new OpenAIAdapter(webClientBuilder.baseUrl(openaiBaseUrl).build()));
        register(new DeepSeekAdapter(webClientBuilder.baseUrl(deepseekBaseUrl).build()));
        register(new ClaudeAdapter(webClientBuilder.baseUrl(claudeBaseUrl).build()));
        log.info("已注册好的LLM适配: openai, deepseek, claude");
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
