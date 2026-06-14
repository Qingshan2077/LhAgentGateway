package com.lh.gateway.mq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列配置
 *
 * <p>使用 Topic Exchange 模式，便于按 provider/model 路由到不同队列。</p>
 */
@Configuration
public class LogQueueConfig {

    @Bean
    public TopicExchange llmExchange() {
        return ExchangeBuilder.topicExchange(QueueNames.LLM_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue callLogQueue() {
        return QueueBuilder.durable(QueueNames.LLM_CALL_LOG)
                .withArgument("x-dead-letter-exchange", QueueNames.LLM_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QueueNames.LLM_CALL_LOG_DLQ)
                .build();
    }

    @Bean
    public Queue callLogDlq() {
        return QueueBuilder.durable(QueueNames.LLM_CALL_LOG_DLQ).build();
    }

    @Bean
    public Binding callLogBinding(TopicExchange exchange, Queue callLogQueue) {
        return BindingBuilder.bind(callLogQueue)
                .to(exchange)
                .with(QueueNames.ROUTING_KEY_LOG + "*");
    }

    @Bean
    public Binding callLogDlqBinding(TopicExchange exchange, Queue callLogDlq) {
        return BindingBuilder.bind(callLogDlq)
                .to(exchange)
                .with(QueueNames.LLM_CALL_LOG_DLQ);
    }
}
