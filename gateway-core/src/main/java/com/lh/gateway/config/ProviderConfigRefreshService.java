package com.lh.gateway.config;

import com.lh.gateway.adapter.AdapterFactory;
import com.lh.gateway.model.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从管理库周期性刷新 Provider 配置，使 gateway-admin CRUD 与网关运行时路由联动。
 * 数据库暂不可用时保留最后一份有效配置，不影响网关继续服务。
 */
@Slf4j
@Component
public class ProviderConfigRefreshService {

    private static final String SELECT_SQL = """
            SELECT id, name, display_name, base_url, api_key, weight,
                   rate_limit_rpm, rate_limit_tpm, enabled, routing_enabled,
                   created_at, updated_at
            FROM provider_config
            ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmProperties llmProperties;
    private final AdapterFactory adapterFactory;
    private volatile String lastSignature = "";

    public ProviderConfigRefreshService(JdbcTemplate jdbcTemplate,
                                        LlmProperties llmProperties,
                                        AdapterFactory adapterFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmProperties = llmProperties;
        this.adapterFactory = adapterFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${llm.provider-config.refresh-ms:5000}")
    public void refresh() {
        try {
            Map<String, ProviderConfig> previous = new HashMap<>();
            llmProperties.getProviders().forEach(config -> previous.put(config.getName(), config));

            List<ProviderConfig> loaded = jdbcTemplate.query(SELECT_SQL, (rs, rowNum) -> {
                ProviderConfig config = new ProviderConfig();
                config.setId(rs.getLong("id"));
                config.setName(rs.getString("name"));
                config.setDisplayName(rs.getString("display_name"));
                config.setBaseUrl(rs.getString("base_url"));
                config.setApiKey(rs.getString("api_key"));
                config.setWeight(rs.getInt("weight"));
                config.setRateLimitRpm(rs.getInt("rate_limit_rpm"));
                config.setRateLimitTpm(rs.getInt("rate_limit_tpm"));
                config.setEnabled(rs.getBoolean("enabled"));
                config.setRoutingEnabled(rs.getBoolean("routing_enabled"));
                var createdAt = rs.getTimestamp("created_at");
                var updatedAt = rs.getTimestamp("updated_at");
                config.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
                config.setUpdatedAt(updatedAt != null ? updatedAt.toLocalDateTime() : null);

                ProviderConfig old = previous.get(config.getName());
                if (old != null) {
                    config.setModels(old.getModels());
                    if ((config.getApiKey() == null || config.getApiKey().isBlank())
                            && old.getApiKey() != null && !old.getApiKey().isBlank()) {
                        config.setApiKey(old.getApiKey());
                    }
                }
                return config;
            });

            if (loaded.isEmpty()) {
                log.warn("Provider configuration table is empty; keeping current gateway configuration");
                return;
            }

            String signature = signature(loaded);
            if (signature.equals(lastSignature)) {
                return;
            }
            List<ProviderConfig> immutable = List.copyOf(loaded);
            llmProperties.setProviders(immutable);
            adapterFactory.reloadAdapters(immutable);
            lastSignature = signature;
            log.info("Gateway Provider configuration refreshed from database: {}", immutable.size());
        } catch (Exception error) {
            log.warn("Provider configuration refresh skipped; keeping last valid configuration: {}",
                    error.getMessage());
        }
    }

    private String signature(List<ProviderConfig> providers) {
        return providers.stream()
                .map(config -> String.join("|",
                        Objects.toString(config.getId(), ""),
                        Objects.toString(config.getName(), ""),
                        Objects.toString(config.getBaseUrl(), ""),
                        Objects.toString(config.getApiKey(), ""),
                        Objects.toString(config.getWeight(), ""),
                        Objects.toString(config.getEnabled(), ""),
                        Objects.toString(config.getRoutingEnabled(), ""),
                        Objects.toString(config.getRateLimitRpm(), ""),
                        Objects.toString(config.getRateLimitTpm(), "")))
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }
}
