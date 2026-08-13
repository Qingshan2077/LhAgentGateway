package com.lh.gateway.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** gateway-admin 专用数据库实体，避免 MyBatis 注解污染 gateway-core 共享配置模型。 */
@TableName("provider_config")
public class ProviderConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String displayName;
    private String baseUrl;
    private String apiKey;
    private Integer weight;
    private Boolean enabled;
    private Boolean routingEnabled;
    private Integer rateLimitRpm;
    private Integer rateLimitTpm;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getRoutingEnabled() { return routingEnabled; }
    public void setRoutingEnabled(Boolean routingEnabled) { this.routingEnabled = routingEnabled; }
    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
    public Integer getRateLimitTpm() { return rateLimitTpm; }
    public void setRateLimitTpm(Integer rateLimitTpm) { this.rateLimitTpm = rateLimitTpm; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
