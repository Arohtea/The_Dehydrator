<h1 align="center">The Dehydrator · 脱水机</h1>

<p align="center">
  <strong>AI 驱动的学术文档分析系统</strong><br/>
  论据链提取 · 逻辑漏洞检测 · 交叉验证
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/FastAPI-0.131-009688?logo=fastapi&logoColor=white" />
  <img src="https://img.shields.io/badge/LLM-OpenAI_Compatible-blue" />
  <img src="https://img.shields.io/badge/License-MIT-yellow" />
</p>

---

## 它能做什么？

上传一篇学术论文或论述文档，The Dehydrator 会自动：

1. **提取论据链**：识别文档中的核心论点、支撑证据及其逻辑关系
2. **检测逻辑漏洞**：发现论证中的逻辑谬误、循环论证、证据不足等问题
3. **交叉验证**：对比论据之间的一致性，找出矛盾和冲突

分析过程实时流式输出，支持随时取消。

---

## 架构

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Web UI    │────▶│ Business Service │────▶│   AI Service    │
│  Vue 3      │     │  Spring Boot     │     │   FastAPI       │
│             │     │                  │     │                 │
└─────────────┘     └──────┬───────────┘     └──┬──────┬───────┘
                           │                    │      │
                    ┌──────┴───────┐     ┌──────┴┐  ┌──┴─────┐
                    │  PostgreSQL  │     │ Qdrant │  │ MinIO  │
                    │    Redis     │     │        │  │        │
                    │   RabbitMQ   │     └────────┘  └────────┘
                    └──────────────┘
```

| 组件 | 职责 |
|------|------|
| **Web UI** | 用户界面，文档上传、分析触发、结果展示 |
| **Business Service** | 业务逻辑，任务调度，SSE 流式推送 |
| **AI Service** | LLM 调用、向量化、分析流水线 |
| **PostgreSQL** | 文档元数据、分析任务、系统设置 |
| **Redis** | 流式输出通道、取消信号 |
| **RabbitMQ** | 异步任务队列（分析请求、结果、进度） |
| **Qdrant** | 文档向量存储与检索 |
| **MinIO** | 原始文档文件存储 |

---

## 配置原则

`docker/.env` 是唯一部署配置源，文件不纳入 Git；可提交的 [docker/.env.example](docker/.env.example) 只提供键名和占位符。应用地址、端口、基础设施账户、密码、内部 Token、管理员认证、超时和容量限制都必须写入真实 `.env`，不要修改 `application.yml` 或另建服务级 `.env`。

文本模型、向量模型、Tavily Key、并发和分块参数是业务数据，只保存在 PostgreSQL 的 `system_settings` 表。AI Service 不读取任何模型环境变量，也不提供模型名称、URL 或 Key 回退。

管理员账户由 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD_HASH` 配置。哈希必须为 BCrypt；真实 `.env` 不保存管理员明文密码。

---

## 启动前准备

- Node.js `^20.19.0` 或 `>=22.12.0`
- Java `17` 与 Maven `3.9+`
- Conda 环境 `dehydrator`，Python `3.11`
- Docker 与 Docker Compose
- 已填写且权限为 `600` 的 `docker/.env`

首次部署时，从示例创建真实配置并逐项替换占位符：

```bash
cp docker/.env.example docker/.env
chmod 600 docker/.env
```

Redis 密码、Qdrant API Key 和 `INTERNAL_SERVICE_TOKEN` 应分别生成，不能复用。管理员 BCrypt 可通过交互式命令生成，避免明文进入命令历史：

```bash
htpasswd -nBC 12 <管理员用户名>
```

---

## 推荐启动顺序

### 1. 启动基础设施

[docker/docker-compose.vm-infra.yml](docker/docker-compose.vm-infra.yml) 负责 PostgreSQL、RabbitMQ、MinIO、Qdrant 和 Redis，并复用 `.env` 指定的外部卷：

```bash
docker compose --env-file docker/.env \
  -f docker/docker-compose.vm-infra.yml up -d
```

不要执行 `down -v`，否则会删除持久化数据。只停止容器时使用同一组参数执行 `down`。

### 2. 启动 Business Service

```bash
cd Business_Service
mvn spring-boot:run
```

Spring Boot 强制导入 `../docker/.env`；文件缺失、必需配置为空、仍为占位符或管理员 BCrypt 无效时会拒绝启动。

### 3. 启动 AI Service

```bash
conda run -n dehydrator python -m pip install -r AI_Service/requirements.txt
cd AI_Service
conda run -n dehydrator python main.py
```

AI Service 直接读取仓库的 `docker/.env`。内部 HTTP 与 RabbitMQ 共用同一个 `INTERNAL_SERVICE_TOKEN` 和消息标识契约。

### 4. 启动 Web UI

```bash
cd Web_ui
npm ci
npm run dev
```

开发服务器的监听地址、端口和 API 代理均来自 `docker/.env`。实际访问地址以 `WEB_DEV_HOST` 和 `WEB_DEV_PORT` 为准。

### 5. 配置 AI 能力

登录后进入“设置”，按需保存：

- 文本模型的名称、OpenAI 兼容接口 URL 和 API Key
- 向量模型的名称、OpenAI 兼容接口 URL 和 API Key
- Tavily API Key
- `mapWorkers`、`chunkSize`、`chunkOverlap`

模型三项可以整组留空；一旦填写必须完整。缺少所需模型时，上传、归档或分析会在外部调用前返回 `422`。深度分析还要求 Tavily Key，快速分析不执行联网搜索。

---

## 常用检查

```bash
# 校验 Compose 与配置插值，不启动服务
docker compose --env-file docker/.env \
  -f docker/docker-compose.vm-infra.yml config --quiet

# 查看基础设施状态
docker compose --env-file docker/.env \
  -f docker/docker-compose.vm-infra.yml ps

# Python 编译检查
conda run -n dehydrator python -m compileall -q AI_Service
```

所有基础设施端口应按 `HOST_BIND_ADDRESS` 绑定。Redis 和 Qdrant 分别要求密码与 API Key；无凭据请求必须被拒绝。

---

## 常见问题

### 配置修改后没有生效

只修改 `docker/.env`。重启对应的本地应用；基础设施配置变化时使用相同 Compose 命令重新创建容器，始终保留外部卷。

### 上传或分析返回 `422`

检查 PostgreSQL 设置行中的模型三项是否完整。上传需要向量模型；归档需要文本模型；分析需要文本和向量模型，深度分析还需要 Tavily Key。

### AI Service 启动但异步任务不消费

检查 RabbitMQ 认证、队列/交换机配置以及 Business Service 与 AI Service 是否从同一份 `docker/.env` 启动。

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3 · Vite · Tailwind CSS · Pinia · Axios |
| 后端 | Spring Boot 3.2 · JPA · AMQP · Java 17 |
| AI | FastAPI · LangChain OpenAI 兼容适配器 · Tavily Search · Qdrant |
| 基础设施 | PostgreSQL 16 · Redis 7 · RabbitMQ 3 · MinIO · Qdrant |
| 部署 | Docker Compose · Nginx · 多阶段构建 |

---

## 项目结构

```
The_Dehydrator/
├── Web_ui/                          # 前端 - Vue 3
│   ├── src/views/                   # 页面组件
│   ├── src/components/              # 复用组件
│   ├── src/api/                     # API 调用
│   └── src/stores/                  # Pinia 状态
├── Business_Service/                # 后端 - Spring Boot
│   └── src/main/java/com/arohtea/business_service/
│       ├── controller/              # REST 控制器
│       ├── service/                 # 业务逻辑
│       ├── repository/              # 数据访问
│       ├── model/                   # JPA 实体
│       └── config/                  # 基础配置
├── AI_Service/                      # AI 服务 - FastAPI
│   ├── api/routes/                  # API 路由
│   ├── services/                    # 核心分析逻辑
│   ├── prompts/                     # 提示词模板
│   └── config/                      # 配置管理
├── docker/
│   ├── docker-compose.vm-infra.yml  # PostgreSQL / Redis / RabbitMQ / MinIO / Qdrant
│   ├── .env.example                 # 可提交的配置契约
│   └── .env                         # 本地真实配置，不纳入 Git
└── docs/                            # 项目文档
```

---

## License

MIT
