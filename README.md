# 🧠 The Dehydrator · 脱水机

> AI 驱动的学术文档分析系统：上传论文或论述文档，自动提取论据链、检测逻辑漏洞、交叉验证论据之间的一致性。分析过程实时流式输出，可随时取消。

![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)

## ✨ 功能特性

- **论据链提取** — 识别核心论点、支撑证据及其逻辑关系
- **逻辑漏洞检测** — 定位循环论证、证据不足、逻辑谬误
- **交叉验证** — 对比论据之间的一致性，标出矛盾与冲突（深度模式支持联网核验）
- **实时流式输出** — 分析进度与推理依据通过 SSE 实时推送，支持随时取消

## 🚀 快速开始

### 环境要求

| 依赖 | 版本 |
|---|---|
| Node.js | `^20.19.0` 或 `>=22.12.0` |
| Java + Maven | Java 17、Maven 3.9+ |
| Python | 3.11（conda 环境 `dehydrator`） |
| Docker | Docker Compose |

### 1. 配置环境

所有配置集中在 `docker/.env`（唯一配置源，不入 Git）。服务地址、端口、基础设施账号密码、内部 Token、管理员认证、容量限制都在这个文件里改，首次部署需自行创建并填写该文件（权限 `600`）。

> **注意**：不要执行 `docker compose down -v`，会删除持久化数据。

### 2. 启动基础设施

PostgreSQL、Redis、RabbitMQ、MinIO、Qdrant 由 Compose 统一管理：

```bash
docker compose --env-file docker/.env \
  -f docker/docker-compose.vm-infra.yml up -d
```

### 3. 启动三个服务

```bash
# Business Service（Spring Boot，启动时校验 docker/.env，缺失或无效会拒绝启动）
cd Business_Service
mvn spring-boot:run

# AI Service（FastAPI）
cd AI_Service
conda run -n dehydrator python main.py

# Web UI（Vue 3）
cd Web_ui
npm ci
npm run dev
```

### 4. 配置 AI 模型

登录后进入「设置」页，保存文本模型、向量模型、Tavily API Key 及分块参数（`mapWorkers` / `chunkSize` / `chunkOverlap`）。模型三项可以整组留空，一旦填写必须完整；缺少所需模型时，上传、归档或分析会返回 `422`。深度分析还要求 Tavily Key，快速分析不执行联网搜索。

## 🏗️ 架构

三个服务 + 一套基础设施，通过 RabbitMQ、Redis、SSE 串联：

| 组件 | 职责 |
|---|---|
| **Web UI**（Vue 3） | 文档上传、分析触发、结果展示 |
| **Business Service**（Spring Boot） | 业务逻辑、任务调度、SSE 流式推送 |
| **AI Service**（FastAPI） | LLM 调用、向量化、分析流水线 |
| **PostgreSQL** | 文档元数据、分析任务、系统设置 |
| **Redis** | 流式输出通道、取消信号 |
| **RabbitMQ** | 异步任务队列（分析请求、结果、进度） |
| **Qdrant** | 文档向量存储与检索 |
| **MinIO** | 原始文档文件存储 |

主链路：上传文档 → MinIO + PostgreSQL 落库 → AI Service 异步解析、切块、向量化到 Qdrant → 发起分析后任务经 RabbitMQ 下发 → 论据链 MAP-REDUCE，逻辑漏洞检测与交叉验证并行 → 结果经 MQ 落库，token 流经 Redis Stream 由 SSE 推给前端。

## ⚙️ 配置说明

- 模型配置（模型名、URL、API Key、Tavily Key）是业务数据，仅保存在 PostgreSQL 的 `system_settings` 表；AI Service 不读任何模型环境变量，也不提供回退
- 管理员账户由 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD_HASH`（BCrypt）配置
- 基础设施配置有变化时，用相同 Compose 命令重建容器并保留外部卷

## ❓ 常见问题

**改配置没生效？** 只改 `docker/.env`，重启对应服务；基础设施配置变化时重建容器（保留外部卷）。

**上传或分析返回 422？** 设置页的模型三项没填完整：上传需要向量模型，归档需要文本模型，分析两个都要，深度分析还需 Tavily Key。

**AI Service 起了但不消费任务？** 检查 RabbitMQ 认证、队列/交换机配置，以及两个服务是否从同一份 `docker/.env` 启动。

## 📁 项目结构

```
The_Dehydrator/
├── Web_ui/           # Vue 3 前端（views / components / api / stores）
├── Business_Service/ # Spring Boot（controller / service / repository / model / config）
├── AI_Service/       # FastAPI（api/routes / services / prompts / config）
├── docker/           # 基础设施 Compose 与 .env
└── docs/             # 架构与安全文档
```

## 📖 文档

- [系统图册](docs/system-diagrams.md) — ER 图、分析流程、任务状态机、通道语义
- [安全配置](docs/security-configuration.md)

## 📝 License

[MIT](LICENSE)
