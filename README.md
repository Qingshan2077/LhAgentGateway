# lh-agent-gateway

> 面向 Agent 场景的 Java 原生 API 网关


---

## 技术栈

| 模块 | 选型 |
|------|------|
| 语言 | Java 17 |
| 网关框架 | Spring Cloud Gateway (WebFlux) |
| 缓存 | Redis |

## 项目结构

```
lh-agent-gateway/
├── gateway-core/              # 网关核心模块
│   ├── adapter/               # LLM 供应商适配器（接口）
│   ├── cache/                 # 缓存（接口）
│   ├── circuitbreaker/        # 熔断降级
│   ├── gateway/
│   │   ├── config/            # 路由配置
│   │   └── filter/            # 过滤器
│   ├── limiter/               # 限流（接口）
│   ├── model/                 # 数据模型
│   ├── retry/                 # 重试策略
│   ├── router/                # 路由策略（接口）
│   └── config/                # 基础设施配置
└── gateway-admin/             # 管理后台模块
```

## 快速开始

```bash
# 启动 Redis
docker compose up -d

# 编译
mvn clean compile

# 启动网关
mvn -pl gateway-core spring-boot:run
```

---

lh | 2026
