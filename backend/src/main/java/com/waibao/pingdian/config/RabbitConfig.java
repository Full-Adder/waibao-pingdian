package com.waibao.pingdian.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 声明：单一 topic 交换机 + 三个业务队列 + 一个通知延迟重试队列。
 * 延迟队列消息 5 分钟过期后按死信路由键 notification.send 回到主队列。
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "waibao.events";

    public static final String IMPORT_QUEUE = "waibao.import.queue";
    public static final String NOTIFICATION_QUEUE = "waibao.notification.queue";
    public static final String NOTIFICATION_DELAY_QUEUE = "waibao.notification.delay.queue";
    public static final String AI_REVIEW_QUEUE = "waibao.ai-review.queue";

    public static final String ROUTING_IMPORT = "import.run";
    public static final String ROUTING_NOTIFICATION = "notification.send";
    public static final String ROUTING_AI_REVIEW = "ai-review.process";

    public static final int NOTIFICATION_DELAY_TTL_MILLIS = 300_000;

    @Bean
    public TopicExchange waibaoEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue importQueue() {
        return QueueBuilder.durable(IMPORT_QUEUE).build();
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    @Bean
    public Queue aiReviewQueue() {
        return QueueBuilder.durable(AI_REVIEW_QUEUE).build();
    }

    @Bean
    public Queue notificationDelayQueue() {
        return QueueBuilder.durable(NOTIFICATION_DELAY_QUEUE)
                .withArgument("x-message-ttl", NOTIFICATION_DELAY_TTL_MILLIS)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_NOTIFICATION)
                .build();
    }

    @Bean
    public Binding importBinding(Queue importQueue, TopicExchange waibaoEventsExchange) {
        return BindingBuilder.bind(importQueue).to(waibaoEventsExchange).with(ROUTING_IMPORT);
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange waibaoEventsExchange) {
        return BindingBuilder.bind(notificationQueue).to(waibaoEventsExchange).with(ROUTING_NOTIFICATION);
    }

    @Bean
    public Binding aiReviewBinding(Queue aiReviewQueue, TopicExchange waibaoEventsExchange) {
        return BindingBuilder.bind(aiReviewQueue).to(waibaoEventsExchange).with(ROUTING_AI_REVIEW);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
