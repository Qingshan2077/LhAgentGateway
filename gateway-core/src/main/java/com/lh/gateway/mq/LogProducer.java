package com.lh.gateway.mq;

import com.lh.gateway.model.CallLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 日志生产者
 *
 * <p>在网关主流程中异步发送调用日志到 MQ，
 * 不阻塞主响应流程（fire-and-forget 模式）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送调用日志
     */
    public void sendLog(CallLog callLog) {
        try {
            String routingKey = QueueNames.ROUTING_KEY_LOG + callLog.getProvider();
            rabbitTemplate.convertAndSend(QueueNames.LLM_EXCHANGE, routingKey, callLog);
        } catch (Exception e) {
            // 日志发送失败不影响主流程，只记录警告
            log.warn("Failed to send call log to MQ: {}", e.getMessage());
        }
    }

    /**
     * 发送成本统计事件（预留）
     */
    public void sendCostEvent(String provider, int tokens, double cost) {
        try {
            String routingKey = QueueNames.ROUTING_KEY_COST + provider;
            var event = new CostEvent(provider, tokens, cost, System.currentTimeMillis());
            rabbitTemplate.convertAndSend(QueueNames.LLM_EXCHANGE, routingKey, event);
        } catch (Exception e) {
            log.warn("Failed to send cost event to MQ: {}", e.getMessage());
        }
    }

    public record CostEvent(String provider, int tokens, double costUsd, long timestamp) {}
}
