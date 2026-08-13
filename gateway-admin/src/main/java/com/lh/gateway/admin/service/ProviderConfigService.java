package com.lh.gateway.admin.service;

import com.lh.gateway.model.ProviderConfig;
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
        return providerConfigMapper.selectList(null);
    }

    public ProviderConfig getByName(String name) {
        return providerConfigMapper.selectOne(Wrappers.<ProviderConfig>lambdaQuery()
                .eq(ProviderConfig::getName, name));
    }

    public void saveOrUpdate(ProviderConfig config) {
        ProviderConfig existing = getByName(config.getName());
        if (existing != null) {
            config.setId(existing.getId());
            providerConfigMapper.updateById(config);
            log.info("Provider config updated: {}", config.getName());
        } else {
            config.setId(null);
            providerConfigMapper.insert(config);
            log.info("Provider config created: {}", config.getName());
        }
    }

    public void setEnabled(String name, boolean enabled) {
        ProviderConfig config = getByName(name);
        if (config != null) {
            providerConfigMapper.updateEnabled(name, enabled);
            log.info("Provider {} {}", name, enabled ? "enabled" : "disabled");
        }
    }

    public void updateWeight(String name, int weight) {
        providerConfigMapper.updateWeight(name, weight);
        log.info("Provider {} weight updated to {}", name, weight);
    }

    public boolean deleteByName(String name) {
        int rows = providerConfigMapper.delete(Wrappers.<ProviderConfig>lambdaQuery()
                .eq(ProviderConfig::getName, name));
        if (rows > 0) {
            log.info("Provider config deleted: {}", name);
        }
        return rows > 0;
    }
}
