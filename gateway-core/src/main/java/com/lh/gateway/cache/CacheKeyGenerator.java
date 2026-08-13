package com.lh.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

@Component("llmCacheKeyGenerator")
public class CacheKeyGenerator {

    /** 修改 Key 结构时升级版本，避免误读旧格式缓存。 */
    private static final String PREFIX = "v2:";
    private final ObjectMapper objectMapper;

    public CacheKeyGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据租户、真实 Provider 和完整请求 JSON 生成缓存 Key。
     *
     * <p>完整 JSON 会先反序列化为通用对象，再按 Map Key 排序后序列化，
     * 因此 tools、tool_choice、response_format、seed 等当前模型类未显式声明的字段
     * 也会影响缓存 Key；仅调整 JSON 对象字段顺序不会产生新 Key。</p>
     */
    public String generateKey(String requestBody, String provider, String appKey) {
        String canonicalBody = canonicalizeJson(requestBody);
        String namespace = "provider=" + normalize(provider)
                + "|appKey=" + normalize(appKey)
                + "|body=" + canonicalBody;
        return PREFIX + sha256(namespace);
    }

    private String canonicalizeJson(String requestBody) {
        try {
            Object json = objectMapper.readValue(requestBody, Object.class);
            return objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(json);
        } catch (Exception e) {
            // CacheFilter 已完成业务模型解析；这里保留原文兜底，避免缓存功能影响主链路。
            return requestBody;
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
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
