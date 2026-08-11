"""RabbitMQ 分析任务消费者及进度/结果消息发布。"""

import json
import asyncio
import logging
from urllib.parse import quote
import aio_pika
from config.settings import settings
from services.model_config import parse_model_config

log = logging.getLogger(__name__)
_progress_connection = None
_progress_channel = None
_progress_exchange = None
MESSAGE_SOURCE = "ai-service"


def _safe_error(error: Exception | str, request_secrets: list[str] | None = None) -> str:
    """移除错误文本中的凭据，避免消息和日志泄露 API Key 或连接认证信息。"""
    message = str(error)
    for secret in (
        settings.internal_service_token,
        settings.redis_password,
        settings.rabbitmq_password,
        *(request_secrets or []),
    ):
        if secret:
            message = message.replace(secret, "[REDACTED]")
    return message[:500]


async def get_connection():
    """创建带自动恢复能力的 RabbitMQ 异步连接。

    Returns:
        已连接的 `aio_pika` robust connection；调用方负责使用异步上下文关闭。
    """
    username = quote(settings.rabbitmq_user, safe="")
    password = quote(settings.rabbitmq_password, safe="")
    return await aio_pika.connect_robust(
        f"amqp://{username}:{password}"
        f"@{settings.rabbitmq_host}:{settings.rabbitmq_port}/",
        heartbeat=settings.ai_rabbitmq_heartbeat_seconds,
    )


def _get_progress_exchange():
    """获取用于同步发布进度的 Pika 阻塞连接、channel 和 exchange 名称。

    进度回调运行在分析线程中，使用独立的同步连接可以避免直接操作异步事件
    循环；连接按进程缓存，并在断开后重新建立。
    """
    global _progress_connection, _progress_channel, _progress_exchange
    import pika

    if _progress_connection is None or _progress_connection.is_closed:
        _progress_connection = pika.BlockingConnection(pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            heartbeat=settings.ai_rabbitmq_heartbeat_seconds,
            credentials=pika.PlainCredentials(settings.rabbitmq_user, settings.rabbitmq_password),
        ))
        _progress_channel = _progress_connection.channel()
        _progress_exchange = settings.rabbitmq_analysis_exchange
    return _progress_channel, _progress_exchange


async def _publish_failed(task_id: str, error: str):
    """向 Business Service 发布失败结果消息。

    该辅助路径本身失败时只记录日志，因为原始分析消息已经不可重试；任务最终
    状态仍可由 Business Service 的超时或轮询机制处理。
    """
    try:
        conn = await get_connection()
        async with conn:
            ch = await conn.channel()
            exchange = await ch.declare_exchange(
                settings.rabbitmq_analysis_exchange,
                aio_pika.ExchangeType.DIRECT,
                durable=True,
            )
            await exchange.publish(
                aio_pika.Message(json.dumps({
                    "source": MESSAGE_SOURCE,
                    "taskId": task_id,
                    "failed": True,
                    "error": _safe_error(error),
                }, ensure_ascii=False).encode()),
                routing_key=settings.rabbitmq_result_queue,
            )
    except Exception:
        log.error("发送失败消息失败: %s", task_id, exc_info=True)


async def _publish_cancelled(task_id: str):
    """把 AI Service 已观察到的取消信号明确回传给 Business Service。"""
    try:
        conn = await get_connection()
        async with conn:
            ch = await conn.channel()
            exchange = await ch.declare_exchange(
                settings.rabbitmq_analysis_exchange,
                aio_pika.ExchangeType.DIRECT,
                durable=True,
            )
            await exchange.publish(
                aio_pika.Message(json.dumps({
                    "source": MESSAGE_SOURCE,
                    "taskId": task_id,
                    "cancelled": True,
                    "status": "CANCELLED",
                    "currentStep": "已确认终止",
                }, ensure_ascii=False).encode()),
                routing_key=settings.rabbitmq_result_queue,
            )
    except Exception:
        log.error("发送取消确认失败: %s", task_id, exc_info=True)


async def _process_message(message: aio_pika.IncomingMessage):
    """消费一条分析请求并发布进度、成功、失败或取消结果。

    消息确认策略是：成功和已确认取消使用 `ack`，输入/运行失败使用
    `nack(requeue=False)`，避免无效任务在队列中无限重试。耗时的同步分析放入
    工作线程，避免阻塞 RabbitMQ 的异步事件循环。
    """
    task_id = None
    request_secrets = []
    try:
        data = json.loads(message.body.decode())
        task_id = data["taskId"]
        doc_id = data["docId"]
        mode = "quick" if data.get("mode") == "quick" else "deep"
        reference_library_ids = [
            item for item in data.get("referenceLibraryIds", [])
            if isinstance(item, str) and item.strip()
        ][:50]
        for config_key in ("textModel", "vectorModel"):
            model_payload = data.get(config_key)
            if isinstance(model_payload, dict) and isinstance(model_payload.get("apiKey"), str):
                request_secrets.append(model_payload["apiKey"])
        if isinstance(data.get("tavilyApiKey"), str):
            request_secrets.append(data["tavilyApiKey"])
        text_config = parse_model_config(data.get("textModel"), "文本模型")
        vector_config = parse_model_config(data.get("vectorModel"), "向量模型")
        tavily_api_key = data.get("tavilyApiKey")
        map_workers = data.get("mapWorkers")
        if not isinstance(map_workers, int) or not 1 <= map_workers <= 8:
            raise ValueError("mapWorkers 必须在 1 到 8 之间")
        log.info("收到分析任务: %s", task_id)

        def _run_analysis():
            """在线程中执行向量读取、论据提取和两个分析分支。"""

            def report(progress: int, step: str):
                """把当前进度作为独立消息发布，失败时不影响主分析。"""
                try:
                    ch, exchange = _get_progress_exchange()
                    ch.basic_publish(
                        exchange=exchange,
                        routing_key=settings.rabbitmq_progress_queue,
                        body=json.dumps({
                            "source": MESSAGE_SOURCE,
                            "taskId": task_id,
                            "progress": progress,
                            "currentStep": step,
                        }),
                    )
                except Exception:
                    log.warning("进度上报失败", exc_info=True)

            # 只读取分析文档来源的向量，参考资料会在交叉验证阶段按资料库检索。
            report(5, "正在检索文档片段...")
            from services.vector_store import get_document_points
            points = get_document_points(doc_id, source_type="analysis_document")
            chunks = [
                point.payload["text"]
                for point in points
                if point.payload and point.payload.get("text")
            ]
            if not chunks:
                raise ValueError("未找到该文档的内容")
            log.info("doc_id=%s, 检索到 %d 个片段", doc_id, len(chunks))

            from services.argument_chain import annotate_logic_flaws, extract_argument_chain
            from services.logic_flaw import detect_logic_flaws
            from services.cross_validation import cross_validate

            chain = extract_argument_chain(chunks, task_id=task_id, on_progress=report,
                                           text_config=text_config, map_workers=map_workers)

            report(70, "正在检测逻辑漏洞，并进行本地交叉验证" if mode == "quick" else "正在检测逻辑漏洞，并进行联网交叉验证")
            from concurrent.futures import ThreadPoolExecutor
            # 漏洞检测与交叉验证互不依赖；并发执行可以缩短总耗时，但每个分支内部
            # 仍遵守各自的模型、联网和取消策略。
            with ThreadPoolExecutor(max_workers=settings.ai_analysis_branch_workers) as executor:
                f_flaws = executor.submit(detect_logic_flaws, chain, task_id=task_id,
                                          text_config=text_config)
                f_valid = executor.submit(cross_validate, chain, task_id=task_id,
                                          on_progress=report, text_config=text_config,
                                          vector_config=vector_config,
                                          tavily_api_key=tavily_api_key,
                                          map_workers=map_workers, mode=mode,
                                          doc_id=doc_id,
                                          reference_library_ids=reference_library_ids)
                flaws = f_flaws.result()
                validation = f_valid.result()
            chain = annotate_logic_flaws(chain, flaws)

            report(100, "快速分析完成" if mode == "quick" else "深度分析完成")
            return chain, flaws, validation

        chain, flaws, validation = await asyncio.to_thread(_run_analysis)
        from services.stream_publisher import AnalysisCancelled, is_cancelled
        if is_cancelled(task_id):
            raise AnalysisCancelled(task_id)

        result = {
            "source": MESSAGE_SOURCE,
            "taskId": task_id,
            "mode": mode,
            "argumentChain": chain,
            "logicFlaws": flaws,
            "crossValidation": validation,
        }

        conn = await get_connection()
        async with conn:
            ch = await conn.channel()
            exchange = await ch.declare_exchange(
                settings.rabbitmq_analysis_exchange,
                aio_pika.ExchangeType.DIRECT,
                durable=True,
            )
            await exchange.publish(
                aio_pika.Message(json.dumps(result, ensure_ascii=False).encode()),
                routing_key=settings.rabbitmq_result_queue,
            )
        await message.ack()
        log.info("分析完成: %s", task_id)
    except Exception as e:
        from services.stream_publisher import AnalysisCancelled, is_cancelled
        if isinstance(e, AnalysisCancelled) or (task_id and is_cancelled(task_id)):
            await _publish_cancelled(task_id)
            await message.ack()
            log.info("分析已取消: %s", task_id)
        else:
            await message.nack(requeue=False)
            safe_error = _safe_error(e, request_secrets)
            log.error("分析失败: %s", safe_error, exc_info=True)
            if task_id:
                await _publish_failed(task_id, safe_error)


async def start_consumer():
    """声明分析请求队列并启动长期消费回调。

    预取数量固定为 1，确保单个 AI Service 进程不会同时占用多个长任务；连接
    恢复后由 `aio_pika` 重新声明 exchange、queue 和绑定关系。
    """
    conn = await get_connection()
    ch = await conn.channel()
    await ch.set_qos(prefetch_count=1)
    exchange = await ch.declare_exchange(
        settings.rabbitmq_analysis_exchange,
        aio_pika.ExchangeType.DIRECT,
        durable=True,
    )
    queue = await ch.declare_queue(settings.rabbitmq_request_queue, durable=True)
    await queue.bind(exchange, routing_key=settings.rabbitmq_request_queue)
    await queue.consume(_process_message)
    log.info("RabbitMQ 消费者已启动")
