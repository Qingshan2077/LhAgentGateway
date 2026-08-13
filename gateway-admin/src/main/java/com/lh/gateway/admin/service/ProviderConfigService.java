package com.lh.gateway.admin.service;

import com.lh.gateway.model.ProviderConfig;
import com.lh.gateway.admin.entity.ProviderConfigEntity;
import com.lh.gateway.admin.mapper.ProviderConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Provider 配置管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConfigService {

    private final ProviderConfigMapper providerConfigMapper;

    public List<ProviderConfig> listAll() {
        return providerConfigMapper.selectList(null).stream()
                .map(this::toModel)
                .toList();
    }

    public ProviderConfig getByName(String name) {
        ProviderConfigEntity entity = getEntityByName(name);
        return entity != null ? toModel(entity) : null;
    }

    public void saveOrUpdate(ProviderConfig config) {
        ProviderConfigEntity existing = getEntityByName(config.getName());
        ProviderConfigEntity entity = toEntity(config);
        if (existing != null) {
            entity.setId(existing.getId());
            entity.setCreatedAt(existing.getCreatedAt());
            providerConfigMapper.updateById(entity);
            log.info("Provider config updated: {}", config.getName());
        } else {
            entity.setId(null);
            providerConfigMapper.insert(entity);
            log.info("Provider config created: {}", config.getName());
        }
    }

    public void setEnabled(String name, boolean enabled) {
        if (getEntityByName(name) != null) {
            providerConfigMapper.updateEnabled(name, enabled);
            log.info("Provider {} {}", name, enabled ? "enabled" : "disabled");
        }
    }

    public void updateWeight(String name, int weight) {
        providerConfigMapper.updateWeight(name, weight);
        log.info("Provider {} weight updated to {}", name, weight);
    }

    public boolean deleteByName(String name) {
        int rows = providerConfigMapper.delete(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getName, name));
        if (rows > 0) {
            log.info("Provider config deleted: {}", name);
        }
        return rows > 0;
    }

    private ProviderConfigEntity getEntityByName(String name) {
        return providerConfigMapper.selectOne(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getName, name));
    }

    private ProviderConfig toModel(ProviderConfigEntity entity) {
        ProviderConfig model = new ProviderConfig();
        model.setId(entity.getId());
        model.setName(entity.getName());
        model.setDisplayName(entity.getDisplayName());
        model.setBaseUrl(entity.getBaseUrl());
        model.setApiKey(entity.getApiKey());
        model.setWeight(entity.getWeight());
        model.setEnabled(entity.getEnabled());
        model.setRoutingEnabled(entity.getRoutingEnabled());
        model.setRateLimitRpm(entity.getRateLimitRpm());
        model.setRateLimitTpm(entity.getRateLimitTpm());
        model.setCreatedAt(entity.getCreatedAt());
        model.setUpdatedAt(entity.getUpdatedAt());
        return model;
    }

    private ProviderConfigEntity toEntity(ProviderConfig model) {
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setId(model.getId());
        entity.setName(model.getName());
        entity.setDisplayName(model.getDisplayName());
        entity.setBaseUrl(model.getBaseUrl());
        entity.setApiKey(model.getApiKey());
        entity.setWeight(model.getWeight());
        entity.setEnabled(model.getEnabled());
        entity.setRoutingEnabled(model.getRoutingEnabled());
        entity.setRateLimitRpm(model.getRateLimitRpm());
        entity.setRateLimitTpm(model.getRateLimitTpm());
        entity.setCreatedAt(model.getCreatedAt());
        entity.setUpdatedAt(model.getUpdatedAt());
        return entity;
    }
}
