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
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        AtomicBoolean open = new AtomicBoolean(true);
        String startId = normalizeEventId(firstNonBlank(lastEventId, queryEventId, "0-0"));

        Runnable close = () -> open.set(false);
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());

        try {
            streamExecutor.execute(() -> pump(taskId, startId, emitter, open));
        } catch (RuntimeException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    /**
     * 先回放历史事件，再持续读取新事件直到终态、连接关闭或超时。
     *
     * @param taskId 任务 ID
     * @param startId 客户端最后确认的事件 ID
     * @param emitter 当前 SSE 响应
     * @param open 连接是否仍可写入的共享标记
     */
    private void pump(String taskId, String startId, SseEmitter emitter, AtomicBoolean open) {
        String cursor = startId;
        try {
            List<MapRecord<String, Object, Object>> history = streamService.replay(taskId);
            for (MapRecord<String, Object, Object> record : history) {
                if (!isAfter(record.getId().getValue(), startId)) {
                    continue;
                }
                cursor = sendRecord(record, emitter);
                if (streamService.isTerminal(streamService.payload(record))) {
                    emitter.complete();
                    return;
                }
            }

            while (open.get()) {
                List<MapRecord<String, Object, Object>> records = streamService.readAfter(
                        taskId, cursor, Duration.ofMillis(readBlockMs));
                if (records.isEmpty()) {
                    if (sendTerminalFallback(taskId, emitter)) {
                        return;
                    }
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    cursor = sendRecord(record, emitter);
                    if (streamService.isTerminal(streamService.payload(record))) {
                        emitter.complete();
                        return;
                    }
                }
            }
        } catch (Exception exception) {
            if (open.get()) {
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
     * @param taskId 任务 ID
     * @param emitter 当前 SSE 响应
     * @return 已发送终态并关闭连接时返回 true
     * @throws Exception SSE 写入失败
     */
    private boolean sendTerminalFallback(String taskId, SseEmitter emitter) throws Exception {
        AnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !TERMINAL_STATUSES.contains(task.getStatus())) {
            return false;
        }
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
