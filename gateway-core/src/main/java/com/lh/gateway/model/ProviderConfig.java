package com.lh.gateway.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Provider 配置模型
 */
public class ProviderConfig {
    private String name;
    private String displayName;
    private String baseUrl;
    private String apiKey;
    private Map<String, ModelConfig> models;
    private Integer weight;
    private Boolean enabled = true;
    /** 是否允许由 Gateway 按 OpenAI 兼容协议直接转发；false 时仍可作为适配器降级候选。 */
    private Boolean routingEnabled = true;
    private Integer rateLimitRpm;
    private Integer rateLimitTpm;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return !Boolean.FALSE.equals(enabled); }
    public Boolean getRoutingEnabled() { return routingEnabled; }
    public void setRoutingEnabled(Boolean routingEnabled) { this.routingEnabled = routingEnabled; }
    public boolean isRoutingEnabled() { return !Boolean.FALSE.equals(routingEnabled); }
    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
    public Integer getRateLimitTpm() { return rateLimitTpm; }
    public void setRateLimitTpm(Integer rateLimitTpm) { this.rateLimitTpm = rateLimitTpm; }

    public static class ModelConfig {
        private String name;
        private Integer maxTokens;
        private BigDecimal pricePer1kInputTokens;
        private BigDecimal pricePer1kOutputTokens;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public BigDecimal getPricePer1kInputTokens() { return pricePer1kInputTokens; }
        public void setPricePer1kInputTokens(BigDecimal pricePer1kInputTokens) { this.pricePer1kInputTokens = pricePer1kInputTokens; }
        public BigDecimal getPricePer1kOutputTokens() { return pricePer1kOutputTokens; }
        public void setPricePer1kOutputTokens(BigDecimal pricePer1kOutputTokens) { this.pricePer1kOutputTokens = pricePer1kOutputTokens; }
    }
}
