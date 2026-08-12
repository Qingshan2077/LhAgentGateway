package com.lh.gateway.config;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 完整配置
 *
 * <p>手动声明连接工厂 / 消息转换器 / RabbitTemplate / 监听容器工厂，
 * 因此 application.yml 中排除了 {@code RabbitAutoConfiguration} 也不影响使用；
 * {@code @EnableRabbit} 开启 {@code @RabbitListener} 扫描（LogConsumer 消费调用日志）。</p>
 */
@Configuration
@EnableRabbit
public class RabbitConfig {

    @Bean
    public ConnectionFactory connectionFactory(
            @Value("${spring.rabbitmq.host:${RABBITMQ_HOST:localhost}}") String host,
            @Value("${spring.rabbitmq.port:${RABBITMQ_PORT:5672}}") int port,
            @Value("${spring.rabbitmq.username:${RABBITMQ_USERNAME:guest}}") String username,
            @Value("${spring.rabbitmq.password:${RABBITMQ_PASSWORD:guest}}") String password) {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        return factory;
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setPrefetchCount(100);
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }
}
