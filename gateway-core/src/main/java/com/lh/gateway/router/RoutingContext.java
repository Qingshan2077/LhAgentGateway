package com.lh.gateway.router;

/**
 * 一次 LLM 请求的路由上下文。
 *
 * <p>一致性哈希使用模型、租户与会话组成稳定 Key；没有会话时按 AppKey 保持租户粘性，
 * 两者都不存在时才退化到 requestId。</p>
 */
public record RoutingContext(String model, String appKey, String sessionId, String requestId) {

    public String consistentHashKey() {
        String normalizedModel = hasText(model) ? model.trim() : "unknown-model";
        String tenant = hasText(appKey) ? appKey.trim() : "anonymous";
        String affinity;
        if (hasText(sessionId)) {
            affinity = "session:" + sessionId.trim();
        } else if (hasText(appKey)) {
            affinity = "tenant:" + appKey.trim();
        } else if (hasText(requestId)) {
            affinity = "request:" + requestId.trim();
        } else {
            affinity = "anonymous";
        }
        return normalizedModel + "|" + tenant + "|" + affinity;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
