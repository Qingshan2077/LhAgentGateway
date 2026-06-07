package com.lh.gateway.adapter;

import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import reactor.core.publisher.Mono;

/**
 * LLM 供应商适配器接口
 */
public interface LlmAdapter {

    /** 供应商唯一标识 */
    String providerName();

    /** 调用 LLM（非流式） */
    Mono<LlmResponse> call(LlmRequest request);

    /** 调用 LLM（流式） */
    Mono<String> callStream(LlmRequest request);

    /** 健康检查 */
    Mono<Boolean> healthCheck();
}
