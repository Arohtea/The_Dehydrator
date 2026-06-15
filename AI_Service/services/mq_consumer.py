import json
import asyncio
import logging
import aio_pika
from config.settings import settings

log = logging.getLogger(__name__)
_progress_connection = None
_progress_channel = None
_progress_exchange = None


async def get_connection():
    return await aio_pika.connect_robust(
        f"amqp://{settings.rabbitmq_user}:{settings.rabbitmq_password}"
        f"@{settings.rabbitmq_host}:{settings.rabbitmq_port}/",
        heartbeat=600,
    )


def _get_progress_exchange():
    global _progress_connection, _progress_channel, _progress_exchange
    import pika

    if _progress_connection is None or _progress_connection.is_closed:
        _progress_connection = pika.BlockingConnection(pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            credentials=pika.PlainCredentials(settings.rabbitmq_user, settings.rabbitmq_password),
        ))
        _progress_channel = _progress_connection.channel()
        _progress_exchange = "analysis.exchange"
    return _progress_channel, _progress_exchange


async def _publish_failed(task_id: str, error: str):
    try:
        conn = await get_connection()
        async with conn:
            ch = await conn.channel()
            exchange = await ch.declare_exchange(
                "analysis.exchange", aio_pika.ExchangeType.DIRECT, durable=True
            )
            await exchange.publish(
                aio_pika.Message(json.dumps({"taskId": task_id, "failed": True, "error": error}, ensure_ascii=False).encode()),
                routing_key="analysis.result",
            )
    except Exception:
        log.error("发送失败消息失败: %s", task_id, exc_info=True)


async def _process_message(message: aio_pika.IncomingMessage):
    task_id = None
    try:
        data = json.loads(message.body.decode())
        task_id = data["taskId"]
        doc_id = data["docId"]
        mode = "quick" if data.get("mode") == "quick" else "deep"
        reference_library_ids = [
            item for item in data.get("referenceLibraryIds", [])
            if isinstance(item, str) and item.strip()
        ]
        api_key = data.get("apiKey")
        model = data.get("model")
        map_workers = data.get("mapWorkers")
        log.info("收到分析任务: %s", task_id)

        def _run_analysis():
            def report(progress: int, step: str):
                try:
                    ch, exchange = _get_progress_exchange()
                    ch.basic_publish(
                        exchange=exchange, routing_key="analysis.progress",
                        body=json.dumps({"taskId": task_id, "progress": progress, "currentStep": step}),
                    )
                except Exception:
                    log.warning("进度上报失败", exc_info=True)

            report(5, "正在检索文档片段...")
            from services.vector_store import get_client
            from qdrant_client import models as qmodels
            client = get_client()
            results = client.scroll(
                settings.qdrant_collection,
                scroll_filter=qmodels.Filter(must=[
                    qmodels.FieldCondition(key="doc_id", match=qmodels.MatchValue(value=doc_id)),
                    qmodels.FieldCondition(key="source_type", match=qmodels.MatchValue(value="analysis_document")),
                ]),
                limit=1000,
            )
            chunks = [p.payload["text"] for p in results[0]]
            log.info("doc_id=%s, 检索到 %d 个片段", doc_id, len(chunks))

            from services.argument_chain import extract_argument_chain
            from services.logic_flaw import detect_logic_flaws
            from services.cross_validation import cross_validate

            chain = extract_argument_chain(chunks, task_id=task_id, on_progress=report,
                                           api_key=api_key, model=model, map_workers=map_workers)

            report(70, "正在检测逻辑漏洞 & 快速交叉验证..." if mode == "quick" else "正在检测逻辑漏洞 & 深度交叉验证...")
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=2) as executor:
                f_flaws = executor.submit(detect_logic_flaws, chain, task_id=task_id,
                                          api_key=api_key, model=model)
                f_valid = executor.submit(cross_validate, chain, task_id=task_id,
                                          on_progress=report, api_key=api_key, model=model,
                                          map_workers=map_workers, mode=mode,
                                          doc_id=doc_id,
                                          reference_library_ids=reference_library_ids)
                flaws = f_flaws.result()
                validation = f_valid.result()

            report(100, "快速分析完成" if mode == "quick" else "深度分析完成")
            return chain, flaws, validation

        chain, flaws, validation = await asyncio.to_thread(_run_analysis)

        result = {
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
                "analysis.exchange", aio_pika.ExchangeType.DIRECT, durable=True
            )
            await exchange.publish(
                aio_pika.Message(json.dumps(result, ensure_ascii=False).encode()),
                routing_key="analysis.result",
            )
        await message.ack()
        log.info("分析完成: %s", task_id)
    except Exception as e:
        await message.nack(requeue=False)
        from services.stream_publisher import AnalysisCancelled
        if isinstance(e, AnalysisCancelled):
            log.info("分析已取消: %s", task_id)
        else:
            log.error("分析失败: %s", e, exc_info=True)
            if task_id:
                await _publish_failed(task_id, str(e)[:500])


async def start_consumer():
    conn = await get_connection()
    ch = await conn.channel()
    await ch.set_qos(prefetch_count=1)
    exchange = await ch.declare_exchange(
        "analysis.exchange", aio_pika.ExchangeType.DIRECT, durable=True
    )
    queue = await ch.declare_queue("analysis.request", durable=True)
    await queue.bind(exchange, routing_key="analysis.request")
    await queue.consume(_process_message)
    log.info("RabbitMQ 消费者已启动")
