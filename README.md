<h1 align="center">The Dehydrator · 脱水机</h1>

<p align="center">
  <strong>AI 驱动的学术文档分析系统</strong><br/>
  论据链提取 · 逻辑漏洞检测 · 交叉验证
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/FastAPI-0.131-009688?logo=fastapi&logoColor=white" />
  <img src="https://img.shields.io/badge/LLM-GLM--4-blue" />
  <img src="https://img.shields.io/badge/License-MIT-yellow" />
</p>

---

## 它能做什么？

上传一篇学术论文或论述文档，The Dehydrator 会自动：

1. **提取论据链** — 识别文档中的核心论点、支撑证据及其逻辑关系
2. **检测逻辑漏洞** — 发现论证中的逻辑谬误、循环论证、证据不足等问题
3. **交叉验证** — 对比论据之间的一致性，找出矛盾和冲突

分析过程实时流式输出，支持随时取消。

---

## 架构

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Web UI    │────▶│ Business Service │────▶│   AI Service    │
│  Vue 3      │     │  Spring Boot     │     │   FastAPI       │
│  :80        │     │  :8080           │     │  :8000          │
└─────────────┘     └──────┬───────────┘     └──┬──────┬───────┘
                           │                    │      │
                    ┌──────┴───────┐     ┌──────┴┐  ┌──┴─────┐
                    │  PostgreSQL  │     │ Qdrant │  │ MinIO  │
                    │  Redis       │     │        │  │        │
                    │  RabbitMQ    │     └────────┘  └────────┘
                    └──────────────┘
```

| 组件 | 职责 |
|------|------|
| **Web UI** | 用户界面，文档上传、分析触发、结果展示 |
| **Business Service** | 业务逻辑，任务调度，SSE 流式推送 |
| **AI Service** | LLM 调用，向量化，MAP-REDUCE 分析流水线 |
| **PostgreSQL** | 文档元数据、分析任务、系统设置 |
| **Redis** | 流式输出通道、取消信号 |
| **RabbitMQ** | 异步任务队列（分析请求/结果/进度） |
| **Qdrant** | 文档向量存储与检索 |
| **MinIO** | 原始文档文件存储 |

---

## 快速开始

> 前置要求：[Docker](https://docs.docker.com/get-docker/) + Docker Compose

```bash
git clone https://github.com/arohtea/The_Dehydrator.git
cd The_Dehydrator/docker
docker compose up -d
```

等待所有服务启动后，打开浏览器访问 **http://localhost** 。

首次使用前，进入 **设置页面** 填写智谱 AI 的 API Key（[申请地址](https://open.bigmodel.cn/)）。

### 服务地址

| 服务 | 地址 | 备注 |
|------|------|------|
| Web UI | http://localhost:9090 | 主界面 |
| RabbitMQ 管理台 | http://localhost:15672 | admin / admin |
| MinIO 控制台 | http://localhost:9001 | admin / 12345678 |

### 常用命令

```bash
# 查看日志
cd docker && docker compose logs -f

# 查看单个服务日志
cd docker && docker compose logs -f ai-service

# 停止所有服务
cd docker && docker compose down

# 停止并清除数据
cd docker && docker compose down -v
```

---

## 本地开发

分别启动三个服务进行开发调试：

```bash
# Web UI (:5173)
cd Web_ui && npm install && npm run dev

# Business Service (:8080)
cd Business_Service && mvn spring-boot:run

# AI Service (:8000)
cd AI_Service && pip install -r requirements.txt && python main.py
```

本地开发需要自行启动基础设施，或用 Docker 只启动中间件：

```bash
cd docker && docker compose up -d postgres redis rabbitmq minio minio-init qdrant
```

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3 · Vite · Tailwind CSS · Pinia · Axios |
| 后端 | Spring Boot 3.2 · JPA · AMQP · Java 17 |
| AI | FastAPI · LangChain · 智谱 GLM-4 · Qdrant |
| 基础设施 | PostgreSQL 16 · Redis 7 · RabbitMQ 3 · MinIO · Qdrant |
| 部署 | Docker Compose · Nginx · 多阶段构建 |

---

## 项目结构

```
The_Dehydrator/
├── Web_ui/                # 前端 - Vue 3
│   ├── src/views/         # 页面组件
│   ├── src/api/           # API 调用
│   └── nginx.conf         # 生产环境反向代理
├── Business_Service/      # 后端 - Spring Boot
│   └── src/.../business_service/
│       ├── controller/    # REST 控制器
│       ├── service/       # 业务逻辑
│       ├── model/         # JPA 实体
│       └── config/        # 配置
├── AI_Service/            # AI 服务 - FastAPI
│   ├── api/routes/        # API 路由
│   ├── services/          # 核心分析逻辑
│   ├── prompts/           # LLM 提示词模板
│   └── config/            # 配置管理
└── docker/                # Docker 一键部署
    ├── docker-compose.yml
    ├── .env
    └── deploy.sh
```

---

## License

[MIT](LICENSE)
