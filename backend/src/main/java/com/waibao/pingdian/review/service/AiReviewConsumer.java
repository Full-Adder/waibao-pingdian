package com.waibao.pingdian.review.service;

import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.messages.AiReviewMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * AI 初审消费者：异常交给 Spring AMQP 重试 3 次，
 * 仍失败由 MqFailureRecoverer 将点评转 PENDING_MANUAL 并写审计。
 */
@Component
public class AiReviewConsumer {

    private final ReviewAiProcessor processor;

    public AiReviewConsumer(ReviewAiProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = RabbitConfig.AI_REVIEW_QUEUE)
    public void onMessage(AiReviewMessage message) {
        processor.process(message.reviewId(), message.ip(), message.device(), message.isEdit());
    }
}
