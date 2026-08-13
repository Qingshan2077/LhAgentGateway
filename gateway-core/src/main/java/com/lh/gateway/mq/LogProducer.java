package com.lh.gateway.mq;

import com.lh.gateway.model.CallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.time.Instant;

/**
 * 日志生产者
 *
 * <p>通过专用线程池发送持久化消息，不占用 Netty EventLoop；结合 Publisher Confirm、
 * Mandatory Return 与指数退避重投确认 Broker 已接收。</p>
 */
@Slf4j
@Component
public class LogProducer {

    private final RabbitTemplate rabbitTemplate;
    private final AsyncTaskExecutor logPublisherExecutor;
    private final TaskScheduler retryScheduler;

    private static final int MAX_PUBLISH_ATTEMPTS = 3;

    public LogProducer(RabbitTemplate rabbitTemplate,
                       @Qualifier("logPublisherExecutor") AsyncTaskExecutor logPublisherExecutor,
                       @Qualifier("logPublisherRetryScheduler") TaskScheduler retryScheduler) {
        this.rabbitTemplate = rabbitTemplate;
        this.logPublisherExecutor = logPublisherExecutor;
        this.retryScheduler = retryScheduler;
    }

    /**
     * 发送调用日志
     */
    public void sendLog(CallLog callLog) {
        try {
            logPublisherExecutor.execute(() -> publishCallLog(callLog, 1));
        } catch (TaskRejectedException rejected) {
            log.error("Call log publisher queue is full, requestId={}", callLog.getRequestId(), rejected);
        }
    }

    private void publishCallLog(CallLog callLog, int attempt) {
        try {
            String routingKey = QueueNames.ROUTING_KEY_LOG + callLog.getProvider();
            CorrelationData correlationData = new CorrelationData(callLog.getRequestId() + ":" + attempt);
            rabbitTemplate.convertAndSend(
                    QueueNames.LLM_EXCHANGE,
                    routingKey,
                    callLog,
                    message -> {
                        message.getMessageProperties().setMessageId(callLog.getRequestId());
                        message.getMessageProperties().setTimestamp(new Date());
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    },
                    correlationData);
            correlationData.getFuture().whenComplete((confirm, confirmError) -> {
                boolean returned = correlationData.getReturned() != null;
                if (confirmError != null || confirm == null || !confirm.isAck() || returned) {
                    String reason = confirmError != null ? confirmError.getMessage()
                            : returned ? "message returned as unroutable"
                            : confirm != null ? confirm.getReason() : "missing publisher confirm";
                    scheduleRetry(callLog, attempt, reason);
                }
            });
        } catch (Exception error) {
            scheduleRetry(callLog, attempt, error.getMessage());
        }
    }

    private void scheduleRetry(CallLog callLog, int completedAttempt, String reason) {
        if (completedAttempt >= MAX_PUBLISH_ATTEMPTS) {
            log.error("Call log publish exhausted retries: requestId={}, reason={}",
                    callLog.getRequestId(), reason);
            return;
        }
        int nextAttempt = completedAttempt + 1;
        long delayMs = 200L * (1L << (completedAttempt - 1));
        log.warn("Retrying call log publish: requestId={}, attempt={}, delayMs={}, reason={}",
                callLog.getRequestId(), nextAttempt, delayMs, reason);
        retryScheduler.schedule(() -> {
            try {
                logPublisherExecutor.execute(() -> publishCallLog(callLog, nextAttempt));
            } catch (TaskRejectedException rejected) {
                scheduleRetry(callLog, nextAttempt, "publisher executor saturated");
            }
        }, Instant.now().plusMillis(delayMs));
    }

    /**
     * 发送成本统计事件（预留）
     */
    public void sendCostEvent(String provider, int tokens, double cost) {
        try {
            logPublisherExecutor.execute(() -> {
                try {
                    String routingKey = QueueNames.ROUTING_KEY_COST + provider;
                    var event = new CostEvent(provider, tokens, cost, System.currentTimeMillis());
                    rabbitTemplate.convertAndSend(QueueNames.LLM_EXCHANGE, routingKey, event);
                } catch (Exception error) {
                    log.error("Failed to publish cost event: provider={}", provider, error);
                }
            });
        } catch (TaskRejectedException rejected) {
            log.error("Cost event publisher queue is full, provider={}", provider, rejected);
        }
    }

    public record CostEvent(String provider, int tokens, double costUsd, long timestamp) {}
}
