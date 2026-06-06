package com.lh.gateway.adapter;

import com.lh.gateway.model.LlmRequest;
import com.lh.gateway.model.LlmResponse;
import reactor.core.publisher.Mono;

/**
 * LLM 供应商适配器接口
 */
public interface LlmAdapter {

    /** 供应商 */
    String providerName();

    /** 调用 LLM（非流式） */
    Mono<LlmResponse> call(LlmRequest request);
}
