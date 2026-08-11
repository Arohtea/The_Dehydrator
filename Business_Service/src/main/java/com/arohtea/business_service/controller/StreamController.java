package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import com.arohtea.business_service.service.AnalysisStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 Redis Stream 转换为支持回放、断线续传和心跳的 SSE 接口。
 *
 * <p>浏览器连接建立后，控制器先发送连接建立前已经保存的历史事件，再阻塞等待
 * 新事件。客户端带来的 {@code Last-Event-ID} 或查询参数决定回放起点；Redis 暂时
 * 没有事件时发送心跳，数据库已进入终态但 Stream 没有写成功时则发送终态兜底消息。</p>
 *
 * <p>每个连接都在专用线程池中读取，避免一个长连接占用 Web 请求线程；连接关闭、
 * 超时或写入失败都会把共享标记设为 false，让后台读取循环停止。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
public class StreamController {

    private static final Set<TaskStatus> TERMINAL_STATUSES = Set.of(
            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED);

    private final AnalysisStreamService streamService;
    private final AnalysisTaskRepository taskRepository;
    private final AsyncTaskExecutor streamExecutor;
    private final long sseTimeoutMs;
    private final long readBlockMs;

    public StreamController(
            AnalysisStreamService streamService,
            AnalysisTaskRepository taskRepository,
            @Qualifier("analysisStreamExecutor") AsyncTaskExecutor streamExecutor,
            @Value("${analysis.sse-timeout-ms}") long sseTimeoutMs,
            @Value("${analysis.redis.read-block-ms:5000}") long readBlockMs) {
        this.streamService = streamService;
        this.taskRepository = taskRepository;
        this.streamExecutor = streamExecutor;
        this.sseTimeoutMs = sseTimeoutMs;
        this.readBlockMs = readBlockMs;
    }

    /**
     * 回放并持续订阅指定任务的 Redis Stream。
     *
     * <p>浏览器标准重连使用 Header，部分前端客户端只能传查询参数，因此两者都支持，
     * 且 Header 优先。非法游标从 {@code 0-0} 开始，宁可重复回放少量事件，也不让
     * 客户端因为不可信游标跳过任务结果。</p>
     *
     * @param taskId 分析任务 ID
     * @param lastEventId 浏览器自动重连携带的 SSE 事件 ID
     * @param queryEventId 前端手动重连携带的事件 ID
     * @return 支持历史回放、断线续传和心跳的 SSE 连接
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "lastEventId", required = false) String queryEventId) {
        // SseEmitter 只负责 HTTP 连接生命周期，实际 Redis 读取放到专用线程池。
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        AtomicBoolean open = new AtomicBoolean(true);
        // 选择断点后立即校验格式，避免把任意字符串交给 Redis Stream 解析。
        String startId = normalizeEventId(firstNonBlank(lastEventId, queryEventId, "0-0"));

        Runnable close = () -> open.set(false);
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());

        try {
            // 提交失败通常意味着线程池已关闭或已满，当前连接直接以错误结束。
            streamExecutor.execute(() -> pump(taskId, startId, emitter, open));
        } catch (RuntimeException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    /**
     * 先回放历史事件，再持续读取新事件直到终态、连接关闭或超时。
     *
     * <p>历史回放和实时读取共用一个 cursor。每发送一条记录就推进 cursor，下一次
     * 阻塞读取从新游标继续；收到终态事件立即关闭连接，避免终态之后继续等待无意义
     * 的心跳。</p>
     *
     * @param taskId 任务 ID
     * @param startId 客户端最后确认的事件 ID
     * @param emitter 当前 SSE 响应
     * @param open 连接是否仍可写入的共享标记
     */
    private void pump(String taskId, String startId, SseEmitter emitter, AtomicBoolean open) {
        String cursor = startId;
        try {
            // 第一步补发历史事件，解决连接建立前进度已经产生的问题。
            List<MapRecord<String, Object, Object>> history = streamService.replay(taskId);
            for (MapRecord<String, Object, Object> record : history) {
                // Redis range 会返回整个保留窗口，只发送客户端游标之后的部分。
                if (!isAfter(record.getId().getValue(), startId)) {
                    continue;
                }
                cursor = sendRecord(record, emitter);
                if (streamService.isTerminal(streamService.payload(record))) {
                    emitter.complete();
                    return;
                }
            }

            // 第二步进入实时读取，直到浏览器关闭连接或任务发送终态。
            while (open.get()) {
                List<MapRecord<String, Object, Object>> records = streamService.readAfter(
                        taskId, cursor, Duration.ofMillis(readBlockMs));
                if (records.isEmpty()) {
                    // Stream 写入可能失败但数据库已完成，先用数据库检查终态，再决定是否继续等。
                    if (sendTerminalFallback(taskId, emitter)) {
                        return;
                    }
                    // 没有事件时发送 SSE comment 维持代理和浏览器的连接活跃。
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    // 先发送再推进游标，确保只有客户端真正收到的记录才被视为已处理。
                    cursor = sendRecord(record, emitter);
                    if (streamService.isTerminal(streamService.payload(record))) {
                        emitter.complete();
                        return;
                    }
                }
            }
        } catch (Exception exception) {
            if (open.get()) {
                // 连接已被客户端主动关闭时不再重复报告错误；仍开放时才通知前端异常结束。
                log.debug("SSE 任务流结束: {}", taskId, exception);
                emitter.completeWithError(exception);
            }
        }
    }

    /**
     * 将 Redis 记录转换为带事件 ID 的 SSE 消息。
     *
     * @param record Redis Stream 记录
     * @param emitter 当前 SSE 响应
     * @return 已发送的 Redis Stream 事件 ID
     * @throws Exception SSE 写入失败
     */
    private String sendRecord(
            MapRecord<String, Object, Object> record, SseEmitter emitter) throws Exception {
        String eventId = record.getId().getValue();
        emitter.send(SseEmitter.event().id(eventId).data(streamService.payload(record)));
        return eventId;
    }

    /**
     * Redis 暂无事件时检查数据库终态并发送兜底事件。
     *
     * <p>Redis Stream 是尽力而为的实时通道，数据库才是任务状态的最终来源。如果
     * 任务已经完成/失败/取消但 Stream 没有对应事件，这里生成最小终态消息，防止
     * 前端一直保持连接等待一个永远不会到来的事件。</p>
     *
     * @param taskId 任务 ID
     * @param emitter 当前 SSE 响应
     * @return 已发送终态并关闭连接时返回 true
     * @throws Exception SSE 写入失败
     */
    private boolean sendTerminalFallback(String taskId, SseEmitter emitter) throws Exception {
        // 只在 Redis 本轮没有消息时查询数据库，避免正常实时路径产生额外数据库压力。
        AnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !TERMINAL_STATUSES.contains(task.getStatus())) {
            return false;
        }
        // 兜底消息只包含前端结束连接所需的状态，不伪造缺失的分析结果内容。
        emitter.send(SseEmitter.event().data("{\"kind\":\""
                + task.getStatus().name().toLowerCase()
                + "\",\"status\":\""
                + task.getStatus().name()
                + "\",\"done\":true}"));
        emitter.complete();
        return true;
    }

    /**
     * 比较 Redis Stream 的时间戳-序列号 ID，判断事件是否位于游标之后。
     *
     * @param eventId 待判断事件 ID
     * @param cursor 客户端游标
     * @return 事件严格位于游标之后时返回 true；格式异常时使用字符串兜底比较
     */
    private boolean isAfter(String eventId, String cursor) {
        if (cursor == null || cursor.isBlank() || "0-0".equals(cursor)) {
            return true;
        }
        try {
            String[] eventParts = eventId.split("-", 2);
            String[] cursorParts = cursor.split("-", 2);
            long eventTime = Long.parseLong(eventParts[0]);
            long cursorTime = Long.parseLong(cursorParts[0]);
            if (eventTime != cursorTime) {
                return eventTime > cursorTime;
            }
            return Long.parseLong(eventParts[1]) > Long.parseLong(cursorParts[1]);
        } catch (Exception ignored) {
            return !eventId.equals(cursor);
        }
    }

    /**
     * 选择首个非空事件 ID，优先使用标准 Header 再使用查询参数。
     *
     * @param values 候选事件 ID
     * @return 首个非空值；全部为空时返回 Redis Stream 起点
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "0-0";
    }

    /**
     * 校验客户端事件 ID 格式，拒绝非法游标从头开始回放。
     *
     * @param value 原始事件 ID
     * @return 合法事件 ID 或 `0-0`
     */
    private String normalizeEventId(String value) {
        return value.matches("\\d+-\\d+") ? value : "0-0";
    }
}
