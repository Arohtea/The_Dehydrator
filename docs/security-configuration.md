# 安全部署配置

Business Service 和 AI Service 启动前必须通过部署平台 Secret 或进程环境变量提供以下值，仓库内不保存真实凭据。

## Business Service

必需变量：

- `POSTGRES_PASSWORD`
- `REDIS_PASSWORD`
- `RABBITMQ_USER`
- `RABBITMQ_PASSWORD`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `INTERNAL_SERVICE_TOKEN`
- `ADMIN_PASSWORD_HASH`

可选变量：

- `ADMIN_USERNAME`，默认 `admin`
- `COOKIE_SECURE`，HTTPS 生产环境必须设为 `true`
- `ALLOWED_MODELS`，逗号分隔的模型白名单，默认 `glm-5,glm-4.6`

`ADMIN_PASSWORD_HASH` 必须是 BCrypt 哈希。可使用 Apache `htpasswd` 交互式生成，避免明文密码进入命令历史：

```bash
htpasswd -nBC 12 admin
```

将输出中用户名后面的 `$2y$...` 哈希作为 Secret 保存。

## AI Service

必需变量：

- `ZHIPUAI_API_KEY`
- `INTERNAL_SERVICE_TOKEN`，必须与 Business Service 使用同一值
- `QDRANT_API_KEY`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `REDIS_PASSWORD`
- `RABBITMQ_USER`
- `RABBITMQ_PASSWORD`

缺少上述任一值，或直接使用 `replace-with-` 示例值时，AI Service 会拒绝启动。

## 网络边界

- 只通过 Web UI 暴露 Business Service 的 `/api`。
- AI Service、Redis、RabbitMQ、MinIO 和 Qdrant 不对公网发布。
- 仓库中的基础设施 Compose 仅绑定 `127.0.0.1`，远程部署应改用私有网络或 TLS 入口。
- 凭据轮换后必须同时更新所有服务 Secret，并重启依赖这些凭据的进程。
