package com.lh.gateway.admin.service;

import com.lh.gateway.model.ProviderConfig;
import com.lh.gateway.admin.mapper.ProviderConfigMapper;
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
        return providerConfigMapper.selectById(name);
    }

    public void saveOrUpdate(ProviderConfig config) {
        ProviderConfig existing = providerConfigMapper.selectById(config.getName());
        if (existing != null) {
            providerConfigMapper.updateById(config);
            log.info("Provider config updated: {}", config.getName());
        } else {
            providerConfigMapper.insert(config);
            log.info("Provider config created: {}", config.getName());
        }
    }

    public void setEnabled(String name, boolean enabled) {
        ProviderConfig config = providerConfigMapper.selectById(name);
        if (config != null) {
            // 简化版本：可以直接使用 MyBatis-Plus 的 LambdaUpdate
            providerConfigMapper.updateEnabled(name, enabled);
            log.info("Provider {} {}", name, enabled ? "enabled" : "disabled");
        }
    }

    public void updateWeight(String name, int weight) {
        providerConfigMapper.updateWeight(name, weight);
        log.info("Provider {} weight updated to {}", name, weight);
    }
}
