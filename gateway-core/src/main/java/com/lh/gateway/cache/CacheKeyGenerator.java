package com.lh.gateway.cache;

import com.lh.gateway.model.LlmRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component("llmCacheKeyGenerator")
public class CacheKeyGenerator {

    private static final String PREFIX = "llm:";

    public String generateKey(LlmRequest request) {
        String content = buildContent(request);
        return PREFIX + sha256(content);
    }

    private String buildContent(LlmRequest request) {
        var sb = new StringBuilder();
        sb.append("model=").append(request.getModel()).append("|");
        sb.append("stream=").append(request.getStream()).append("|");
        if (request.getTemperature() != null) sb.append("temp=").append(request.getTemperature()).append("|");
        if (request.getMaxTokens() != null) sb.append("max_tokens=").append(request.getMaxTokens()).append("|");
        if (request.getMessages() != null) {
            for (LlmRequest.Message msg : request.getMessages()) {
                sb.append(msg.getRole()).append(":").append(msg.getContent()).append("|");
            }
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
