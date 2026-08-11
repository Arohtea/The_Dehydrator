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

/**
 * 统一管理分析任务的 Redis Stream 事件写入、回放和清理。
 *
 * <p>数据库任务记录适合保存当前状态，但不适合让多个 SSE 客户端读取“中间过程”。
 * Redis Stream 为每个任务保存一段有序事件：新连接先回放历史，断线重连从上次的
 * Stream ID 继续读取。写入失败时系统仍保留数据库轮询能力，所以实时推送是加速层，
 * 不是任务正确性的唯一来源。</p>
 */
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
     * <p>每条事件都先序列化成一个 {@code data} 字段，再写入任务专属 Stream；写入
     * 成功后裁剪旧记录并刷新 TTL。裁剪限制内存，TTL 保证任务结束后历史事件不会
     * 无限占用 Redis；两步都在写入之后执行，避免新客户端看到不完整的事件序列。</p>
     *
     * @param taskId 任务 ID
     * @param event 事件对象
     * @return Redis Stream 事件 ID，Redis 不可用时返回空字符串
     */
    public String append(String taskId, Map<String, Object> event) {
        try {
            // 一个任务一个 Stream，前端只会收到当前任务的事件，不会混入其他任务。
            String key = streamKey(taskId);
            // 统一保存为 JSON 字符串，SSE 控制器可以原样发送给浏览器。
            String payload = objectMapper.writeValueAsString(event);
            // Redis 自动生成递增 ID，客户端用它作为断线重连游标。
            RecordId recordId = redisTemplate.opsForStream().add(key, Map.of("data", payload));
            // 先写入再裁剪并刷新 TTL，保证新连接可以回放最近事件且不会无限增长。
            redisTemplate.opsForStream().trim(key, streamMaxLength, true);
            redisTemplate.expire(key, Duration.ofSeconds(streamTtlSeconds));
            return recordId == null ? "" : recordId.getValue();
        } catch (Exception exception) {
            // Redis 只是实时通知通道，写入失败不能让已经落库的分析任务回滚或失败。
            log.warn("分析事件流写入失败，继续依赖任务轮询: {}", taskId, exception);
            return "";
        }
    }

    /**
     * 发布任务终态事件。终态事件用于 SSE 自动收口，也保存在回放历史中。
     *
     * <p>{@code done=true} 告诉前端连接可以关闭，{@code status} 告诉前端最终结果，
     * {@code message} 提供失败或取消的可读原因。即使客户端此刻未连接，事件也会
     * 留在 Stream 中，稍后重连仍可看到。</p>
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
     * <p>进度事件不修改任务终态，只负责把后台处理过程推送给当前页面；数据库中的
     * 同步进度由结果监听器保存，页面刷新后仍能恢复最近一次状态。</p>
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
     * <p>SSE 首次连接使用它补齐连接建立前已经产生的事件，断线重连也使用同一方法
     * 获取保留历史。Redis Stream ID 本身保证返回顺序，调用方只需过滤客户端已经
     * 收到的游标。</p>
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
     * <p>Redis 在等待窗口内没有新事件时返回空列表，控制器会把这种情况转换成
     * 心跳或数据库终态兜底；它不是错误，也不代表任务已经停止。</p>
     *
     * @param taskId 任务 ID
     * @param lastEventId 上次已经发送的事件 ID
     * @param blockDuration 单次阻塞时长
     * @return 新事件，超时无事件时返回空列表
     */
    public List<MapRecord<String, Object, Object>> readAfter(
            String taskId, String lastEventId, Duration blockDuration) {
        // 从上次已经发送的 ID 之后开始读，避免断线重连重复发送同一条事件。
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(100).block(blockDuration),
                StreamOffset.create(streamKey(taskId), ReadOffset.from(lastEventId))
        );
        return records == null ? List.of() : records;
    }

    /**
     * 删除任务对应的事件流。
     *
     * <p>任务的数据库记录和外部资源都已完成清理后才调用此方法；如果过早删除，
     * 正在连接的页面会失去历史回放依据。</p>
     *
     * @param taskId 任务 ID
     */
    public void delete(String taskId) {
        redisTemplate.delete(streamKey(taskId));
    }

    /**
     * 判断事件是否代表任务已经进入终态。
     *
     * <p>终态识别只看事件的 {@code kind} 字段，不依赖事件的其他可选内容，保证
     * 成功、失败和取消的不同 payload 都能让 SSE 连接正确收口。</p>
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
