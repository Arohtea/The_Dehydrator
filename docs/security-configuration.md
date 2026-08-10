# 安全部署配置

## 单一配置源

所有部署配置只保存在未跟踪的 `docker/.env`。可提交的 `docker/.env.example` 只定义键名和占位符，不包含真实账户、密码、Token、哈希或服务地址。

真实文件至少应满足：

- 权限为 `600`
- 不存在空值、`replace-with-` 或 `change-me` 占位符
- PostgreSQL、RabbitMQ、MinIO 沿用既有账户时，与现有数据卷中的账户一致
- Redis 密码、Qdrant API Key、`INTERNAL_SERVICE_TOKEN` 分别生成且不复用
- `HOST_BIND_ADDRESS` 不向不可信网络暴露基础设施

Business Service、AI Service、Vite、Nginx 和 Compose 都消费这份配置。`INTERNAL_SERVICE_TOKEN` 只有一个配置键，因此内部 HTTP 调用不会维护第二份 Token。

## 管理员账户

`ADMIN_USERNAME` 与 `ADMIN_PASSWORD_HASH` 都是必填项，不提供默认账户。`ADMIN_PASSWORD_HASH` 必须是 BCrypt，真实 `.env` 不保存明文密码。

使用交互式命令生成哈希，避免明文进入命令历史：

```bash
htpasswd -nBC 12 <管理员用户名>
```

将输出中冒号后的 BCrypt 值写入 `ADMIN_PASSWORD_HASH`。启动校验会拒绝空值、占位符和无效 BCrypt。

## AI 配置边界

以下内容只能保存在 PostgreSQL 的 `system_settings` 表：

- 文本模型名称、OpenAI 兼容接口 URL、API Key
- 向量模型名称、OpenAI 兼容接口 URL、API Key
- Tavily API Key
- `mapWorkers`、`chunkSize`、`chunkOverlap`

AI Service 不读取模型环境变量，也不维护模型回退值。模型三项可以整组为空，但不能只填写一部分；缺少调用所需配置时，Business Service 在上传、归档或分析前返回 `422`。

## 网络与数据

- 基础设施端口统一按 `HOST_BIND_ADDRESS` 绑定。
- Redis 要求密码认证，Qdrant 要求 API Key。
- 应用管理员认证使用 HTTP Session 和 CSRF，不启用默认账户或 HTTP Basic。
- PostgreSQL、RabbitMQ、MinIO、Qdrant 和 Redis 使用 `.env` 指定的外部卷。
- 重建容器时不得使用 `down -v`；数据库结构变更前必须生成并校验备份。
- 生产环境启用 HTTPS 时，`COOKIE_SECURE` 必须设为 `true`。
