package com.lh.gateway.adapter;

import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claude 适配器（格式转换）
 */
public class ClaudeAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAdapter.class);

    private final WebClient webClient;

    public ClaudeAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String providerName() {
        return "claude";
    }

    @Override
    public Mono<LlmResponse> call(LlmRequest request) {
        Map<String, Object> claudeRequest = convertToClaudeFormat(request);

        return webClient.post()
                .uri("/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .bodyValue(claudeRequest)
                .retrieve()
                .bodyToMono(LlmResponse.class)
                .doOnSuccess(resp -> log.debug("Claude call succeeded"))
                .doOnError(err -> log.error("Claude call failed: {}", err.getMessage()));
    }

    @Override
    public Mono<String> callStream(LlmRequest request) {
        request.setStream(true);
        Map<String, Object> claudeRequest = convertToClaudeFormat(request);

        return webClient.post()
                .uri("/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .bodyValue(claudeRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .reduce(String::concat);
    }

    @Override
    public Mono<Boolean> healthCheck() {
        return webClient.get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(String.class)
                .hasElement()
                .onErrorReturn(false);
    }

    private Map<String, Object> convertToClaudeFormat(LlmRequest request) {
        StringBuilder systemContent = new StringBuilder();
        var messages = new ArrayList<Map<String, Object>>();

        if (request.getMessages() != null) {
            for (LlmRequest.Message msg : request.getMessages()) {
                if ("system".equals(msg.getRole())) {
                    systemContent.append(msg.getContent()).append("\n");
                } else {
                    messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
                }
            }
        }

        var claudeBody = new LinkedHashMap<String, Object>();
        claudeBody.put("model", request.getModel());
        if (!systemContent.isEmpty()) {
            claudeBody.put("system", systemContent.toString().trim());
        }
        claudeBody.put("messages", messages);
        claudeBody.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 4096);
        if (request.getTemperature() != null) {
            claudeBody.put("temperature", request.getTemperature());
        }
        return claudeBody;
    }
}
