# lh-agent-gateway

> 面向 Agent 场景的 Java 原生 API 网关 — 统一 LLM 调用管理、限流、缓存、熔断、路由与成本追踪
>
> 项目状态：已完成全部代码开发

---

## 背景

Java 后端团队在接入多个 LLM（OpenAI、Claude、DeepSeek 等）时，面临四个痛点：

- **无统一限流**：多个微服务共享 API Key，易触发 Rate Limit
- **重复请求**：相同 Prompt 被多次调用，浪费 Token 和成本
- **单点故障**：某个供应商宕机 → 整个 Agent 不可用
- **无成本追踪**：月底算账困难

市面方案（LiteLLM、Portkey）均为 Python/Node.js，**Java/Spring 生态中面向 Agent 的 API 网关仍是空白**。

## 技术栈

| 模块 | 选型 |
|------|------|
| 语言 | Java 17 |
| 网关框架 | Spring Cloud Gateway 4.1 (WebFlux) |
| 限流 | Redis + Lua 令牌桶 |
| 缓存 | Caffeine (本地) + Redis (分布式) |
| 熔断 | Resilience4j + 自实现滑动窗口 |
| 路由 | 加权轮询 / 最小延迟 / 一致性哈希 |
| 消息队列 | RabbitMQ（异步日志 + 削峰） |
| 监控 | Prometheus + Grafana |
| 数据库 | MySQL + MyBatis-Plus（调用日志） |

## 项目结构

```
lh-agent-gateway/
├── gateway-core/              # 网关核心模块
│   ├── adapter/               # LLM 供应商适配器
│   ├── cache/                 # 多级缓存
│   ├── circuitbreaker/        # 熔断降级
│   ├── gateway/               # 网关路由 & 过滤器
│   │   ├── config/            # 路由配置
│   │   └── filter/            # 过滤器链
│   ├── limiter/               # 分布式限流
│   ├── model/                 # 数据模型
│   ├── monitor/               # 监控指标
│   ├── mq/                    # 消息队列
│   ├── retry/                 # 重试策略
│   ├── router/                # 路由策略
│   └── config/                # 基础设施配置
├── gateway-admin/             # 管理后台模块
├── benchmark/                 # 压测脚本 & 报告
└── docs/                      # 文档
```

## 快速开始

```bash
# 1. 启动基础设施
cd lh-agent-gateway
docker compose up -d

docker start lh-gateway-redis lh-mysql lh-rabbit
# 2. 编译
./mvnw clean compile

# 3. 启动网关
./mvnw -pl gateway-core spring-boot:run

# 4. 测试代理
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Provider: openai" \
  -H "X-Api-Key: test-key" \
  -d '{"model":"gpt-3.5-turbo","messages":[{"role":"user","content":"Hello"}]}'
  
# 5.前端界面
cd gateway-admin-ui
npm install
npm run dev
```

## 开发进度

| 模块 | 状态 | 计划 |
|------|------|------|
| 项目骨架 & Maven 配置 | 完成 | Day 1 |
| API 模型定义 | 完成 | Day 1 |
| Provider 适配接口 | 完成 | Day 1 |
| 限流、缓存、熔断接口 | 完成 | Day 1 |
| Docker 开发环境 | 完成 | Day 1 |
| **适配器实现** | 完成 | Day 2 |
| **Redis 令牌桶限流** | 完成 | Day 3 |
| **网关过滤器链** | 完成 | Day 4 |
| **多级缓存** | 完成 | Day 5 |
| **熔断降级** | 完成 | Day 6 |
| **路由策略** | 完成 | Day 7 |
| **RabbitMQ 异步日志** | 完成 | Day 9 |
| **Prometheus 监控** | 完成 | Day 10 |
| **管理后台** | 完成 | Day 11 |
| **压测 & 文档** | 完成 | Day 12-13 |

## 架构概览

```
客户端 → 认证 → 限流(Redis令牌桶) → 缓存(多级)
       → 熔断(滑动窗口) → 路由(加权轮询) → 适配器(LLM)
       → 响应 → 异步写日志(RabbitMQ)
```

---

lh | 2026
