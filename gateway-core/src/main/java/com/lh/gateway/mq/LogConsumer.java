package com.lh.gateway.mq;

import com.lh.gateway.model.CallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 日志消费者
 *
 * <p>异步消费调用日志消息，写入数据库。
 * 支持消费者限流（prefetchCount=100），避免消息堆积。</p>
 */
@Slf4j
@Component
public class LogConsumer {

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
            // TODO: 写入 MySQL
            // callLogMapper.insert(callLog);
            log.debug("Call log consumed: requestId={}, provider={}, tokens={}",
                    callLog.getRequestId(), callLog.getProvider(), callLog.getTotalTokens());
        } catch (Exception e) {
            log.error("Failed to persist call log: requestId={}", callLog.getRequestId(), e);
            // 消费失败的消息会自动进入 DLQ
            throw new RuntimeException("Failed to persist call log", e);
        }
    }
}
