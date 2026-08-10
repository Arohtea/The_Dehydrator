<h1 align="center">The Dehydrator · 脱水机</h1>

<p align="center">
  <strong>AI 驱动的学术文档分析系统</strong><br/>
  论据链提取 · 逻辑漏洞检测 · 交叉验证
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/FastAPI-0.131-009688?logo=fastapi&logoColor=white" />
  <img src="https://img.shields.io/badge/LLM-GLM--5-blue" />
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
│  :5173(dev) │     │  :8080           │     │  :8000          │
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

## 先看这个

当前仓库**没有**“一条 `docker compose up -d` 就把整套系统拉起来”的完整编排。

仓库里现有的 [docker/docker-compose.vm-infra.yml](docker/docker-compose.vm-infra.yml) 只会启动：

- `RabbitMQ`
- `MinIO`
- `Qdrant`
- `Redis`

要完整跑通系统，你还需要额外准备并启动：

- `PostgreSQL`
- `Business_Service`
- `AI_Service`
- `Web_ui`

所以旧文档里这些说法现在都不准确：

- `docker/docker-compose.yml`
- `http://localhost:9090`
- “Docker 一键启动全部服务”

---

## 启动前准备

- Node.js `^20.19.0` 或 `>=22.12.0`
- Java `17`
- Maven `3.9+`
- Python `3.11`
- Docker + Docker Compose
- PostgreSQL `16+`
- Redis `7+`

默认本地端口和配置来源如下：

| 组件 | 默认地址/端口 | 配置来源 |
|------|---------------|----------|
| Web UI | `http://localhost:5173` | [Web_ui/vite.config.js](Web_ui/vite.config.js) |
| Business Service | `http://localhost:8080` | [Business_Service/src/main/resources/application.yml](Business_Service/src/main/resources/application.yml) |
| AI Service | `http://localhost:8000` | [AI_Service/main.py](AI_Service/main.py) |
| PostgreSQL | `localhost:5432` | [Business_Service/src/main/resources/application.yml](Business_Service/src/main/resources/application.yml) |
| Redis | `localhost:6379` | [Business_Service/src/main/resources/application.yml](Business_Service/src/main/resources/application.yml) |
| RabbitMQ | `localhost:5672` / `15672` | [docker/docker-compose.vm-infra.yml](docker/docker-compose.vm-infra.yml) |
| MinIO | `localhost:9000` / `9001` | [docker/docker-compose.vm-infra.yml](docker/docker-compose.vm-infra.yml) |
| Qdrant | `localhost:6333` / `6334` | [docker/docker-compose.vm-infra.yml](docker/docker-compose.vm-infra.yml) |

---

## 推荐启动顺序

### 1. 先准备 PostgreSQL

`Business_Service` 默认连接配置如下：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dehydrator
    username: postgres
    password: ${POSTGRES_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379
```

因此至少要先满足这几个条件：

1. PostgreSQL 已启动
2. 已创建数据库 `dehydrator`

如果你不用默认地址，请修改 [Business_Service/src/main/resources/application.yml](Business_Service/src/main/resources/application.yml)。

后端当前使用 `spring.jpa.hibernate.ddl-auto=update`，首次启动时会自动建表或补表。

### 2. 再启动仓库自带基础设施

```bash
cd docker
docker compose -f docker-compose.vm-infra.yml up -d
```

这个 compose 只会启动：

- `rabbitmq`
- `minio`
- `minio-init`
- `qdrant`
- `redis`

不会启动 `PostgreSQL`，也不会启动三段应用服务。

### 3. 配置 AI Service

`AI_Service` 依赖本地 `.env`。仓库里有 [AI_Service/.env.example](AI_Service/.env.example)，但其中的 `192.168.1.4` 是示例值，不能直接照抄到本机。

建议你的 `AI_Service/.env` 至少包含：

```bash
ZHIPUAI_API_KEY=你的智谱 API Key
ZHIPUAI_MODEL=glm-5
ZHIPUAI_TIMEOUT=300

QDRANT_HOST=localhost
QDRANT_PORT=6333
QDRANT_API_KEY=${QDRANT_API_KEY}

MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=${MINIO_ACCESS_KEY}
MINIO_SECRET_KEY=${MINIO_SECRET_KEY}

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PASSWORD}

RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=${RABBITMQ_USER}
RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
INTERNAL_SERVICE_TOKEN=${INTERNAL_SERVICE_TOKEN}
```

补充两点：

- Web UI 设置页里保存的 API Key 会优先透传给 AI Service
- 如果设置页没有保存 API Key，AI Service 会回退到 `.env` 中的 `ZHIPUAI_API_KEY`

### 4. 启动 Business Service

```bash
cd Business_Service
mvn spring-boot:run
```

如果你的 PostgreSQL、Redis、MinIO、AI Service 不在默认地址，先改 [Business_Service/src/main/resources/application.yml](Business_Service/src/main/resources/application.yml)。

### 5. 启动 AI Service

```bash
cd AI_Service
pip install -r requirements.txt
python main.py
```

启动成功后可访问：

- `http://localhost:8000/health`

如果日志里出现 `RabbitMQ 连接失败，仅HTTP模式`，说明 AI Service 虽然启动了，但异步分析任务不会正常消费，需要先检查 RabbitMQ。

### 6. 启动 Web UI

```bash
cd Web_ui
npm install
npm run dev
```

打开：

- `http://localhost:5173`

前端开发服务器会把 `/api` 请求代理到 `http://localhost:8080`。

### 7. 首次进入系统后的配置

进入“设置”页面，至少确认这些值：

- 智谱 API Key
- 模型名称
- `mapWorkers`
- `chunkSize`
- `chunkOverlap`

这些配置会保存到 PostgreSQL 的 `system_settings` 表，并参与后续分析任务。

---

## 服务地址与自检

| 服务 | 地址 | 自检方式 |
|------|------|----------|
| Web UI（开发） | http://localhost:5173 | 页面能正常打开 |
| Business Service | http://localhost:8080 | 登录后访问 `http://localhost:8080/api/settings` |
| AI Service | http://localhost:8000 | 打开 `http://localhost:8000/health` |
| RabbitMQ 管理台 | http://localhost:15672 | 使用部署 Secret 登录 |
| MinIO API | http://localhost:9000 | 供后端和 AI Service 使用 |
| MinIO 控制台 | http://localhost:9001 | 使用部署 Secret 登录 |
| Qdrant | http://localhost:6333 | 向量库 API |
| PostgreSQL | localhost:5432 | `dehydrator` 数据库可连接 |
| Redis | localhost:6379 | 使用密码认证后能正常读写键值 |

推荐按这个顺序做一次冒烟验证：

1. `http://localhost:8000/health` 返回 `{"status":"ok"}`
2. `http://localhost:8080/api/settings` 能返回 JSON
3. Web UI 能正常加载并保存设置
4. 上传文档后，MinIO 与 Qdrant 有新增数据
5. 发起分析后，RabbitMQ、Redis、SSE 流有实时变化

---

## 常用命令

```bash
# 启动 RabbitMQ / MinIO / Qdrant / Redis
cd docker
docker compose -f docker-compose.vm-infra.yml up -d

# 查看基础设施日志
docker compose -f docker-compose.vm-infra.yml logs -f

# 查看单个服务日志
docker compose -f docker-compose.vm-infra.yml logs -f rabbitmq
docker compose -f docker-compose.vm-infra.yml logs -f minio
docker compose -f docker-compose.vm-infra.yml logs -f qdrant
docker compose -f docker-compose.vm-infra.yml logs -f redis

# 停止基础设施
docker compose -f docker-compose.vm-infra.yml down

# 停止并清理卷数据
docker compose -f docker-compose.vm-infra.yml down -v
```

---

## 常见问题

### 1. `docker compose up -d` 提示找不到 `docker-compose.yml`

仓库里目前没有 `docker/docker-compose.yml`，要显式指定：

```bash
docker compose -f docker/docker-compose.vm-infra.yml up -d
```

### 2. 前端能打开，但接口全是 404 或 502

先检查：

- `Business_Service` 是否运行在 `8080`
- [Web_ui/vite.config.js](Web_ui/vite.config.js) 中的代理目标是否仍是 `http://localhost:8080`

### 3. AI Service 已启动，但分析任务一直卡住

重点排查：

- RabbitMQ 是否正常监听 `5672`
- Redis 是否正常监听 `6379`
- `Business_Service` 是否成功把任务发到 `analysis.exchange`
- 设置页里是否保存了可用的 API Key

### 4. 上传成功，但分析时报向量或对象存储相关错误

重点排查：

- Qdrant `6333` 是否可达
- MinIO `9000` 是否可达
- `AI_Service/.env` 中的主机和端口是否与实际一致

### 5. `docker/.env` 里明明有 PostgreSQL / Redis，为什么 compose 没启动它们？

当前本地运行时，真正决定行为的文件是：

- [docker/docker-compose.vm-infra.yml](docker/docker-compose.vm-infra.yml)
- [Business_Service/src/main/resources/application.yml](Business_Service/src/main/resources/application.yml)
- `AI_Service/.env`

不要只看 `docker/.env` 就推断“仓库已经能完整一键启动”。

---

## Docker 现状

仓库里给三段应用都提供了 `Dockerfile`，但目前还没有把下面这些内容统一编排进一个完整 compose：

- `Web_ui`
- `Business_Service`
- `AI_Service`
- `PostgreSQL`
- `Redis`

因此当前最稳妥的运行方式仍然是：

1. Docker 启动基础设施
2. 本地分别启动三段应用

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3 · Vite · Tailwind CSS · Pinia · Axios |
| 后端 | Spring Boot 3.2 · JPA · AMQP · Java 17 |
| AI | FastAPI · LangChain · 智谱 GLM-5 · Qdrant |
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
│   ├── docker-compose.vm-infra.yml  # RabbitMQ / MinIO / Qdrant
│   └── .env
└── docs/                            # 项目文档
```

---

## License

MIT
