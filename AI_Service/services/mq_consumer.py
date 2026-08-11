"""RabbitMQ 分析任务消费者及进度/结果消息发布。"""

import json
import asyncio
import logging
from urllib.parse import quote
import aio_pika
from config.settings import settings
from services.model_config import parse_model_config

log = logging.getLogger(__name__)
_consumer_connection = None
_progress_channel = None
_progress_exchange = None
_PROGRESS_PUBLISH_TIMEOUT_SECONDS = 5
MESSAGE_SOURCE = "ai-service"


def _safe_error(error: Exception | str, request_secrets: list[str] | None = None) -> str:
    """移除错误文本中的凭据，避免消息和日志泄露 API Key 或连接认证信息。"""
    # 先把异常转换成文本；不同库抛出的异常类型不同，但最终都要通过同一套脱敏规则。
    message = str(error)
    # 认证信息可能来自运行时配置，也可能只存在于当前 RabbitMQ 请求中，二者都要清理。
    for secret in (
        settings.internal_service_token,
        settings.redis_password,
        settings.rabbitmq_password,
        *(request_secrets or []),
    ):
        # 空字符串不能参与 replace，否则会把原文中的每个字符之间都插入替换文本。
        if secret:
            message = message.replace(secret, "[REDACTED]")
    # 只把有限长度的错误发送给 Java 服务和日志，避免第三方异常携带超大响应正文。
    return message[:500]


async def get_connection():
    """创建带自动恢复能力的 RabbitMQ 异步连接。

    Returns:
        已连接的 `aio_pika` robust connection；调用方负责使用异步上下文关闭。
    """
    # 用户名和密码可能包含 @、: 等 URL 保留字符，必须先编码再拼接 AMQP 地址。
    username = quote(settings.rabbitmq_user, safe="")
    password = quote(settings.rabbitmq_password, safe="")
    # robust connection 会在网络短暂中断后自动恢复，调用方仍负责在应用关闭时释放它。
    return await aio_pika.connect_robust(
        f"amqp://{username}:{password}"
        f"@{settings.rabbitmq_host}:{settings.rabbitmq_port}/",
        heartbeat=settings.ai_rabbitmq_heartbeat_seconds,
    )


async def _publish_progress(task_id: str, progress: int, step: str):
    """在 RabbitMQ 所属事件循环中发布进度消息。

    Args:
        task_id: 分析任务 ID。
        progress: 当前进度百分比。
        step: 当前处理步骤。

    Raises:
        RuntimeError: 进度发布通道尚未初始化。
    """
    # 进度发布使用独立的全局 exchange；消费者尚未启动时不能静默丢弃调用错误。
    if _progress_exchange is None:
        raise RuntimeError("RabbitMQ 进度发布通道未初始化")
    # 进度只携带前端展示需要的字段，source/taskId 让 Business Service 能校验来源并定位任务。
    await _progress_exchange.publish(
        aio_pika.Message(json.dumps({
            "source": MESSAGE_SOURCE,
            "taskId": task_id,
            "progress": progress,
            "currentStep": step,
        }, ensure_ascii=False).encode()),
        routing_key=settings.rabbitmq_progress_queue,
    )


async def _publish_failed(task_id: str, error: str):
    """向 Business Service 发布失败结果消息。

    该辅助路径本身失败时只记录日志，因为原始分析消息已经不可重试；任务最终
    状态仍可由 Business Service 的超时或轮询机制处理。
    """
    try:
        # 失败通知不能复用消费通道的生命周期，单独建立短连接可以避免消费连接已损坏时无法回报。
        conn = await get_connection()
        async with conn:
            # exchange 使用和请求相同的 durable direct 拓扑，保证消息路由与 Java 配置一致。
            ch = await conn.channel()
            exchange = await ch.declare_exchange(
                settings.rabbitmq_analysis_exchange,
                aio_pika.ExchangeType.DIRECT,
                durable=True,
            )
            # 先发布失败结果，再由上层决定原始请求是否 ack/nack；结果消息不包含真实 Secret。
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
        # 原任务已经进入失败路径，失败通知本身失败时只能记录日志，不能再次抛出覆盖原异常。
        log.error("发送失败消息失败: %s", task_id, exc_info=True)


async def _publish_cancelled(task_id: str):
    """把 AI Service 已观察到的取消信号明确回传给 Business Service。"""
    try:
        # 取消确认必须到达 Business Service，Java 侧才能把 CANCELLING 收口为 CANCELLED。
        conn = await get_connection()
        async with conn:
            ch = await conn.channel()
            exchange = await ch.declare_exchange(
                settings.rabbitmq_analysis_exchange,
                aio_pika.ExchangeType.DIRECT,
                durable=True,
            )
            # cancelled/status/currentStep 共同表达“AI Service 已看到取消信号并停止”。
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
        # 回报失败不会重新执行已经取消的模型调用，只记录，最终由 Java 超时/轮询兜底。
        log.error("发送取消确认失败: %s", task_id, exc_info=True)


async def _process_message(message: aio_pika.IncomingMessage):
    """消费一条分析请求并发布进度、成功、失败或取消结果。

    消息确认策略是：成功和已确认取消使用 `ack`，输入/运行失败使用
    `nack(requeue=False)`，避免无效任务在队列中无限重试。耗时的同步分析放入
    工作线程，避免阻塞 RabbitMQ 的异步事件循环。
    """
    # 先保留任务 ID 和请求中的 Secret；无论解析在哪一步失败，异常路径都需要它们。
    task_id = None
    request_secrets = []
    try:
        # RabbitMQ body 是 bytes，先解码再解析 JSON，结构错误会进入统一失败处理。
        data = json.loads(message.body.decode())
        # taskId 用于回报结果，docId 用于从 Qdrant 读取原始分析文档向量。
        task_id = data["taskId"]
        doc_id = data["docId"]
        # 只有显式 quick 才是快速模式，其余值保持与 Business Service 的兼容约定按 deep 处理。
        mode = "quick" if data.get("mode") == "quick" else "deep"
        # 过滤空值并限制数量，避免异常消息让后续检索范围或消息体无限扩大。
        reference_library_ids = [
            item for item in data.get("referenceLibraryIds", [])
            if isinstance(item, str) and item.strip()
        ][:50]
        # 先收集待脱敏的 Key，再解析配置；后续任何错误都能安全写入日志。
        for config_key in ("textModel", "vectorModel"):
            model_payload = data.get(config_key)
            if isinstance(model_payload, dict) and isinstance(model_payload.get("apiKey"), str):
                request_secrets.append(model_payload["apiKey"])
        if isinstance(data.get("tavilyApiKey"), str):
            request_secrets.append(data["tavilyApiKey"])
        # 文本模型负责分析，向量模型负责检索参考资料；两套配置必须分别校验。
        text_config = parse_model_config(data.get("textModel"), "文本模型")
        vector_config = parse_model_config(data.get("vectorModel"), "向量模型")
        tavily_api_key = data.get("tavilyApiKey")
        map_workers = data.get("mapWorkers")
        # mapWorkers 来自数据库设置，AI Service 再做一次边界校验，防止不可信消息开过多线程。
        if not isinstance(map_workers, int) or not 1 <= map_workers <= 8:
            raise ValueError("mapWorkers 必须在 1 到 8 之间")
        log.info("收到分析任务: %s", task_id)
        # 后台分析线程不能直接 await，保存当前事件循环供 report 回到异步线程发布进度。
        analysis_loop = asyncio.get_running_loop()

        def _run_analysis():
            """在线程中执行向量读取、论据提取和两个分析分支。"""

            def report(progress: int, step: str):
                """把当前进度作为独立消息发布，失败时不影响主分析。"""
                # 进度上报是旁路能力；future 超时或失败不能让主分析任务失败。
                future = None
                try:
                    future = asyncio.run_coroutine_threadsafe(
                        _publish_progress(task_id, progress, step),
                        analysis_loop,
                    )
                    # 同步等待一个很短的窗口，让进度有机会发出但不拖住分析线程。
                    future.result(timeout=_PROGRESS_PUBLISH_TIMEOUT_SECONDS)
                except Exception:
                    if future is not None:
                        future.cancel()
                    log.warning("进度上报失败", exc_info=True)

            # 只读取分析文档来源的向量，参考资料会在交叉验证阶段按资料库检索。
            # 分析只读取 source_type=analysis_document 的向量，参考资料由交叉验证阶段单独过滤。
            report(5, "正在检索文档片段...")
            from services.vector_store import get_document_points
            points = get_document_points(doc_id, source_type="analysis_document")
            # Qdrant point 是资源层对象，业务分析只需要 payload 中非空的文本片段。
            chunks = [
                point.payload["text"]
                for point in points
                if point.payload and point.payload.get("text")
            ]
            if not chunks:
                raise ValueError("未找到该文档的内容")
            log.info("doc_id=%s, 检索到 %d 个片段", doc_id, len(chunks))

            # 延迟导入重量级模块，消费者启动时不必立即加载全部模型和解析依赖。
            from services.argument_chain import annotate_logic_flaws, extract_argument_chain
            from services.logic_flaw import detect_logic_flaws
            from services.cross_validation import cross_validate

            # 先完成论据链，这是逻辑漏洞检测和交叉验证共同依赖的中间结果。
            chain = extract_argument_chain(chunks, task_id=task_id, on_progress=report,
                                           text_config=text_config, map_workers=map_workers)

            report(70, "正在检测逻辑漏洞，并进行本地交叉验证" if mode == "quick" else "正在检测逻辑漏洞，并进行联网交叉验证")
            from concurrent.futures import ThreadPoolExecutor
            # 漏洞检测与交叉验证互不依赖；并发执行可以缩短总耗时，但每个分支内部
            # 仍遵守各自的模型、联网和取消策略。
            # 两条分支都读取同一份 chain 但写入不同结果；并行执行缩短总耗时。
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
                # result() 会在当前线程等待分支完成，异常会回到外层统一取消/失败处理。
                flaws = f_flaws.result()
                validation = f_valid.result()
            # 把漏洞汇总标记回论据步骤，前端可以在同一条论据链上直接显示风险。
            chain = annotate_logic_flaws(chain, flaws)

            report(100, "快速分析完成" if mode == "quick" else "深度分析完成")
            return chain, flaws, validation

        # 同步模型调用、Qdrant 读取和线程池等待放到工作线程，避免阻塞 RabbitMQ 事件循环。
        chain, flaws, validation = await asyncio.to_thread(_run_analysis)
        from services.stream_publisher import AnalysisCancelled, is_cancelled
        # 线程内部完成后再检查一次取消，覆盖“最后一个 token 之后才到达取消信号”的窗口。
        if is_cancelled(task_id):
            raise AnalysisCancelled(task_id)

        # 结果消息是 Java 服务更新任务终态的唯一完整数据来源。
        result = {
            "source": MESSAGE_SOURCE,
            "taskId": task_id,
            "mode": mode,
            "argumentChain": chain,
            "logicFlaws": flaws,
            "crossValidation": validation,
        }

        # 分析结果使用独立连接发布，发送成功后才确认原始请求已处理。
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
        # ack 放在结果发布之后，避免先确认消息再因网络故障丢失最终结果。
        await message.ack()
        log.info("分析完成: %s", task_id)
    except Exception as e:
        from services.stream_publisher import AnalysisCancelled, is_cancelled
        # 明确取消和普通失败使用不同确认策略：取消已完成业务收口，失败不应无限重回队列。
        if isinstance(e, AnalysisCancelled) or (task_id and is_cancelled(task_id)):
            await _publish_cancelled(task_id)
            await message.ack()
            log.info("分析已取消: %s", task_id)
        else:
            # 输入错误、模型错误或运行异常都不重新入队，避免坏任务阻塞后续分析。
            await message.nack(requeue=False)
            safe_error = _safe_error(e, request_secrets)
            log.error("分析失败: %s", safe_error, exc_info=True)
            # nack 后再回报失败，Java 服务可以把已提交任务标记为 FAILED。
            if task_id:
                await _publish_failed(task_id, safe_error)


async def start_consumer():
    """声明分析请求队列并启动长期消费回调。

    预取数量固定为 1，确保单个 AI Service 进程不会同时占用多个长任务；连接
    恢复后由 `aio_pika` 重新声明 exchange、queue 和绑定关系。
    """
    global _consumer_connection, _progress_channel, _progress_exchange

    # 消费连接负责读取请求队列，进度发布另开 channel，避免发布慢时影响消费确认。
    _consumer_connection = await get_connection()
    ch = await _consumer_connection.channel()
    # 每个进程只预取一条长任务，防止多个任务同时占满本地模型和 Qdrant 资源。
    await ch.set_qos(prefetch_count=1)
    exchange = await ch.declare_exchange(
        settings.rabbitmq_analysis_exchange,
        aio_pika.ExchangeType.DIRECT,
        durable=True,
    )
    # durable exchange/queue/binding 与 Java 端拓扑一致，RabbitMQ 重启后仍能恢复。
    queue = await ch.declare_queue(settings.rabbitmq_request_queue, durable=True)
    await queue.bind(exchange, routing_key=settings.rabbitmq_request_queue)

    # 进度发布 channel 只保存 exchange 引用，不参与请求消息的 ack/nack。
    _progress_channel = await _consumer_connection.channel()
    _progress_exchange = await _progress_channel.declare_exchange(
        settings.rabbitmq_analysis_exchange,
        aio_pika.ExchangeType.DIRECT,
        durable=True,
    )
    # 注册回调后由 aio-pika 持续把消息交给 _process_message。
    await queue.consume(_process_message)
    log.info("RabbitMQ 消费者已启动")


async def stop_consumer():
    """关闭消费者及进度发布共用的 RabbitMQ 连接。

    Returns:
        None.
    """
    global _consumer_connection, _progress_channel, _progress_exchange

    # 先清空全局引用，阻止新的进度发布继续使用正在关闭的 channel。
    connection = _consumer_connection
    _consumer_connection = None
    _progress_channel = None
    _progress_exchange = None
    # 应用可能在消费者尚未成功启动时关闭，因此连接为空也是合法路径。
    if connection is not None and not connection.is_closed:
        await connection.close()
