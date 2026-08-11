package com.arohtea.business_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 统一管理分析任务的 Redis Stream 事件写入、回放和清理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisStreamService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${analysis.redis.stream-prefix}")
    private String streamPrefix;
    @Value("${analysis.redis.stream-max-length:10000}")
    private long streamMaxLength;
    @Value("${analysis.redis.stream-ttl-seconds:86400}")
    private long streamTtlSeconds;

    /**
     * 发布一个结构化分析事件，并刷新任务流的生命周期。
     *
     * @param taskId 任务 ID
     * @param event 事件对象
     * @return Redis Stream 事件 ID，Redis 不可用时返回空字符串
     */
    public String append(String taskId, Map<String, Object> event) {
        try {
            String key = streamKey(taskId);
            String payload = objectMapper.writeValueAsString(event);
            RecordId recordId = redisTemplate.opsForStream().add(key, Map.of("data", payload));
            // 先写入再裁剪并刷新 TTL，保证新连接可以回放最近事件且不会无限增长。
            redisTemplate.opsForStream().trim(key, streamMaxLength, true);
            redisTemplate.expire(key, Duration.ofSeconds(streamTtlSeconds));
            return recordId == null ? "" : recordId.getValue();
        } catch (Exception exception) {
            log.warn("分析事件流写入失败，继续依赖任务轮询: {}", taskId, exception);
            return "";
        }
    }

    /**
     * 发布任务终态事件。终态事件用于 SSE 自动收口，也保存在回放历史中。
     *
     * @param taskId 任务 ID
     * @param status 任务终态
     * @param message 面向用户的可读说明，可为空
     */
    public void publishTerminal(String taskId, String status, String message) {
        append(taskId, Map.of(
                "kind", status.toLowerCase(),
                "status", status,
                "done", true,
                "message", message == null ? "" : message
        ));
    }

    /**
     * 发布进度事件，使详情页在轮询间隔内也能同步当前步骤。
     *
     * @param taskId 任务 ID
     * @param progress 百分比
     * @param currentStep 当前步骤
     */
    public void publishProgress(String taskId, int progress, String currentStep) {
        append(taskId, Map.of(
                "kind", "progress",
                "progress", progress,
                "currentStep", currentStep == null ? "" : currentStep,
                "done", false
        ));
    }

    /**
     * 回放指定任务的全部保留事件。
     *
     * @param taskId 任务 ID
     * @return 按 Redis Stream ID 排序的事件记录
     */
    public List<MapRecord<String, Object, Object>> replay(String taskId) {
        // 回放接口用于 SSE 首次连接和断线重连，保留事件顺序由 Redis Stream ID 保证。
        return redisTemplate.opsForStream().range(streamKey(taskId), Range.unbounded());
    }

    /**
     * 阻塞读取指定事件 ID 之后的新事件。
     *
     * @param taskId 任务 ID
     * @param lastEventId 上次已经发送的事件 ID
     * @param blockDuration 单次阻塞时长
     * @return 新事件，超时无事件时返回空列表
     */
    public List<MapRecord<String, Object, Object>> readAfter(
            String taskId, String lastEventId, Duration blockDuration) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(100).block(blockDuration),
                StreamOffset.create(streamKey(taskId), ReadOffset.from(lastEventId))
        );
        return records == null ? List.of() : records;
    }

    /**
     * 删除任务对应的事件流。
     *
     * @param taskId 任务 ID
     */
    public void delete(String taskId) {
        redisTemplate.delete(streamKey(taskId));
    }

    /**
     * 判断事件是否代表任务已经进入终态。
     *
     * @param payload SSE 数据内容
     * @return 是否为完成、失败或取消事件
     */
    public boolean isTerminal(String payload) {
        try {
            String kind = objectMapper.readTree(payload).path("kind").asText("").toLowerCase();
            return kind.equals("completed") || kind.equals("failed") || kind.equals("cancelled");
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 读取 Stream 记录中的 JSON 数据字段。
     *
     * @param record Redis Stream 记录
     * @return JSON 数据，字段缺失时返回空字符串
     */
    public String payload(MapRecord<String, Object, Object> record) {
        Object value = record.getValue().get("data");
        return value == null ? "" : value.toString();
    }

    /**
     * 生成任务专属 Redis Stream Key。
     *
     * @param taskId 分析任务 ID
     * @return 带部署前缀的 Stream Key
     */
    private String streamKey(String taskId) {
        return streamPrefix + taskId;
    }
}
