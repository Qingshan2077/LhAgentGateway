package com.lh.gateway.adapter;

import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * DeepSeek 适配器（兼容 OpenAI 格式）
 */
public class DeepSeekAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAdapter.class);

    private final WebClient webClient;
    private final String apiKey;

    public DeepSeekAdapter(WebClient webClient, String apiKey) {
        this.webClient = webClient;
        this.apiKey = apiKey;
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    @Override
    public Mono<LlmResponse> call(LlmRequest request) {
        return webClient.post()
                .uri("/v1/chat/completions")
                .headers(headers -> setAuthorization(headers))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmResponse.class)
                .doOnSuccess(resp -> log.debug("DeepSeek call succeeded"))
                .doOnError(err -> log.error("DeepSeek call failed: {}", err.getMessage()));
    }

    @Override
    public Mono<String> callStream(LlmRequest request) {
        request.setStream(true);
        return webClient.post()
                .uri("/v1/chat/completions")
                .headers(headers -> setAuthorization(headers))
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .reduce(String::concat);
    }

    @Override
    public Mono<Boolean> healthCheck() {
        return webClient.get()
                .uri("/v1/models")
                .headers(headers -> setAuthorization(headers))
                .retrieve()
                .bodyToMono(String.class)
                .hasElement()
                .onErrorReturn(false);
    }

    private void setAuthorization(org.springframework.http.HttpHeaders headers) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
    }
}
