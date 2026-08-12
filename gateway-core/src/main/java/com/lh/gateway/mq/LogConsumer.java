package com.lh.gateway.mq;

import com.lh.gateway.model.CallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 日志消费者
 *
 * <p>异步消费调用日志消息，写入 MySQL {@code llm_call_log} 表。
 * 支持消费者限流（prefetchCount=100），避免消息堆积。
 * 落库失败抛出异常 → 消息进死信队列（DLQ）重试。</p>
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
     * <br>prefetch=100：每次预取 100 条，批量处理更高效。</p>
     */
    @RabbitListener(queues = QueueNames.LLM_CALL_LOG,
            concurrency = "3-5",
            containerFactory = "rabbitListenerContainerFactory")
    public void consumeCallLog(CallLog callLog) {
        try {
            LocalDateTime createdAt = callLog.getCreatedAt() != null ? callLog.getCreatedAt() : LocalDateTime.now();
            int rows = jdbcTemplate.update(INSERT_SQL,
                    callLog.getRequestId(),
                    callLog.getAppKey(),
                    callLog.getProvider(),
                    callLog.getModel(),
                    callLog.getPromptTokens(),
                    callLog.getCompletionTokens(),
                    callLog.getTotalTokens(),
                    callLog.getCostUsd(),
                    callLog.getLatencyMs(),
                    callLog.getStatus(),
                    callLog.getErrorMessage(),
                    Timestamp.valueOf(createdAt));
            log.debug("Call log persisted: requestId={}, rows={}", callLog.getRequestId(), rows);
        } catch (Exception e) {
            log.error("Failed to persist call log: requestId={}, err={}",
                    callLog.getRequestId(), e.getMessage());
            // 消费失败的消息进入 DLQ
            throw new RuntimeException("Failed to persist call log", e);
        }
    }
}
