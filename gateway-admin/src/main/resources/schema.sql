-- lh-agent-gateway 数据库初始化脚本
-- 数据库: agent_gateway

-- 调用日志表
CREATE TABLE IF NOT EXISTS llm_call_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id      VARCHAR(64)  NOT NULL COMMENT '全局请求ID',
    app_key         VARCHAR(64)           COMMENT '调用方标识',
    provider        VARCHAR(32)  NOT NULL COMMENT '供应商 (openai/claude/deepseek)',
    model           VARCHAR(64)  NOT NULL COMMENT '模型名',
    prompt_tokens   INT          DEFAULT 0 COMMENT '输入 Token 数',
    completion_tokens INT        DEFAULT 0 COMMENT '输出 Token 数',
    total_tokens    INT          DEFAULT 0 COMMENT '总 Token 数',
    cost_usd        DECIMAL(10,6) DEFAULT 0 COMMENT '美元成本',
    latency_ms      INT          DEFAULT 0 COMMENT '延迟 (ms)',
    status          VARCHAR(16)  NOT NULL COMMENT '状态 (success/fail/timeout/limited)',
    error_message   VARCHAR(512)          COMMENT '错误信息',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_created_at (created_at),
    INDEX idx_provider_model (provider, model),
    INDEX idx_app_key (app_key),
    INDEX idx_status (status),
    UNIQUE INDEX idx_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 调用日志表';

-- 兼容已初始化过的库（幂等落库 INSERT IGNORE 依赖 request_id 唯一索引）：
-- 若已存在该索引会报重复，可忽略；新库上面的 CREATE TABLE 已包含。
-- ALTER TABLE llm_call_log ADD UNIQUE INDEX idx_request_id (request_id);

-- Provider 配置表（管理后台动态配置）
CREATE TABLE IF NOT EXISTS provider_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name            VARCHAR(32)  NOT NULL COMMENT '供应商标识 (openai/claude/deepseek)',
    display_name    VARCHAR(64)  NOT NULL COMMENT '展示名',
    base_url        VARCHAR(256) NOT NULL COMMENT 'API 基础地址',
    api_key         VARCHAR(256) NOT NULL COMMENT 'API Key (加密存储)',
    weight          INT          DEFAULT 1 COMMENT '路由权重',
    rate_limit_rpm  INT          DEFAULT 60 COMMENT '每分钟请求数限制',
    rate_limit_tpm  INT          DEFAULT 100000 COMMENT '每分钟 Token 数限制',
    enabled         TINYINT      DEFAULT 1 COMMENT '是否启用',
    routing_enabled TINYINT      DEFAULT 1 COMMENT '是否允许 OpenAI 兼容协议直转',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Provider 配置表';

-- 兼容旧表结构；MySQL 8 支持 IF NOT EXISTS。
ALTER TABLE provider_config
    ADD COLUMN IF NOT EXISTS routing_enabled TINYINT DEFAULT 1 COMMENT '是否允许 OpenAI 兼容协议直转';

-- 插入默认 Provider 配置
INSERT INTO provider_config (name, display_name, base_url, api_key, weight, rate_limit_rpm, rate_limit_tpm) VALUES
('openai',   'OpenAI',   'https://api.openai.com',    '', 3, 200,  100000),
('deepseek', 'DeepSeek', 'https://api.deepseek.com',  '', 2, 500,  500000),
('claude',   'Claude',   'https://api.anthropic.com',  '', 1, 100,  80000)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);
