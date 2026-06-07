package com.lh.gateway.adapter;

import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * OpenAI 适配器
 */
public class OpenAIAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAdapter.class);

    private final WebClient webClient;

    public OpenAIAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public Mono<LlmResponse> call(LlmRequest request) {
        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmResponse.class)
                .doOnSuccess(resp -> log.debug("OpenAI call succeeded"))
                .doOnError(err -> log.error("OpenAI call failed: {}", err.getMessage()));
    }

    @Override
    public Mono<String> callStream(LlmRequest request) {
        request.setStream(true);
        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
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
}
