package com.lh.gateway.mq;

import com.lh.gateway.model.CallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日志消费者
 *
 * <p>异步消费调用日志消息，写入 MySQL {@code llm_call_log} 表。
 * 使用 consumer batch 将最多 100 条消息合并为一次 JDBC batchUpdate。
 * 落库失败时整批拒绝并进入死信队列（DLQ），等待独立重放。</p>
 *
 * <p>幂等：{@code INSERT IGNORE} + {@code request_id} 唯一索引，
 * 重复投递的消息直接忽略，保证"至少一次"投递语义下业务结果正确。</p>
 */
@Slf4j
@Component
public class LogConsumer {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO llm_call_log
                (request_id, app_key, provider, model,
                 prompt_tokens, completion_tokens, total_tokens,
                 cost_usd, latency_ms, status, error_message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public LogConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 消费调用日志
     *
     * <p>concurrency=3-5：核心消费者 3，最多 5 个并发处理。
     * <br>prefetch=100 + consumerBatchEnabled：每批最多 100 条执行一次批量 INSERT。</p>
     */
    @RabbitListener(queues = QueueNames.LLM_CALL_LOG,
            concurrency = "3-5",
            containerFactory = "rabbitListenerContainerFactory")
    public void consumeCallLog(List<CallLog> callLogs) {
        try {
            List<Object[]> batchArguments = callLogs.stream()
                    .map(this::toArguments)
                    .toList();
            int[] rows = jdbcTemplate.batchUpdate(INSERT_SQL, batchArguments);
            int affected = java.util.Arrays.stream(rows).filter(value -> value > 0).sum();
            log.debug("Call log batch persisted: batchSize={}, affectedRows={}", callLogs.size(), affected);
        } catch (Exception e) {
            log.error("Failed to persist call log batch: batchSize={}, err={}",
                    callLogs.size(), e.getMessage());
            // defaultRequeueRejected=false：整批明确拒绝并进入 DLQ，不在主队列无限重投。
            throw new RuntimeException("Failed to persist call log", e);
        }
    }

    private Object[] toArguments(CallLog callLog) {
        LocalDateTime createdAt = callLog.getCreatedAt() != null
                ? callLog.getCreatedAt() : LocalDateTime.now();
        return new Object[]{
                callLog.getRequestId(), callLog.getAppKey(), callLog.getProvider(), callLog.getModel(),
                callLog.getPromptTokens(), callLog.getCompletionTokens(), callLog.getTotalTokens(),
                callLog.getCostUsd(), callLog.getLatencyMs(), callLog.getStatus(),
                callLog.getErrorMessage(), Timestamp.valueOf(createdAt)
        };
    }
}
