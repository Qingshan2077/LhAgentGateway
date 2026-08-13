package com.lh.gateway.admin.controller;

import com.lh.gateway.model.ProviderConfig;
import com.lh.gateway.admin.service.ProviderConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Provider 配置管理接口
 */
@RestController
@RequestMapping("/api/admin/providers")
@RequiredArgsConstructor
public class ProviderConfigController {

    private final ProviderConfigService providerConfigService;

    /**
     * 获取所有 Provider 配置
     */
    @GetMapping
    public ResponseEntity<List<ProviderConfig>> listAll() {
        return ResponseEntity.ok(providerConfigService.listAll());
    }

    /**
     * 获取单个 Provider 配置
     */
    @GetMapping("/{name}")
    public ResponseEntity<ProviderConfig> getByName(@PathVariable("name") String name) {
        ProviderConfig config = providerConfigService.getByName(name);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /**
     * 创建/更新 Provider 配置
     */
    @PutMapping("/{name}")
    public ResponseEntity<Void> saveOrUpdate(
            @PathVariable("name") String name,
            @RequestBody ProviderConfig config) {
        config.setName(name);
        providerConfigService.saveOrUpdate(config);
        return ResponseEntity.ok().build();
    }

    /**
     * 启用 Provider
     */
    @PostMapping("/{name}/enable")
    public ResponseEntity<Void> enable(@PathVariable("name") String name) {
        providerConfigService.setEnabled(name, true);
        return ResponseEntity.ok().build();
    }

    /**
     * 禁用 Provider
     */
    @PostMapping("/{name}/disable")
    public ResponseEntity<Void> disable(@PathVariable("name") String name) {
        providerConfigService.setEnabled(name, false);
        return ResponseEntity.ok().build();
    }

    /**
     * 更新路由权重
     */
    @PatchMapping("/{name}/weight")
    public ResponseEntity<Void> updateWeight(
            @PathVariable("name") String name,
            @RequestParam("weight") int weight) {
        providerConfigService.updateWeight(name, weight);
        return ResponseEntity.ok().build();
    }

    /** 删除 Provider 配置。 */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        return providerConfigService.deleteByName(name)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
