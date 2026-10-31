package com.waibao.pingdian.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.company.service.ImportJobRunner;
import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.messages.AiReviewMessage;
import com.waibao.pingdian.mq.messages.ImportMessage;
import com.waibao.pingdian.review.service.ReviewAiProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/**
 * Spring AMQP 重试（默认 3 次）耗尽后的兜底：让业务记录落到可继续处理的状态，并拒绝重投。
 * 通知队列的业务失败已在 NotificationSender 内落库，这里只记录日志。
 */
@Component
public class MqFailureRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(MqFailureRecoverer.class);

    private final ObjectMapper objectMapper;
    private final ImportJobRunner importJobRunner;
    private final ReviewAiProcessor reviewAiProcessor;

    public MqFailureRecoverer(ObjectMapper objectMapper,
                              ImportJobRunner importJobRunner,
                              ReviewAiProcessor reviewAiProcessor) {
        this.objectMapper = objectMapper;
        this.importJobRunner = importJobRunner;
        this.reviewAiProcessor = reviewAiProcessor;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        String queue = message.getMessageProperties().getConsumerQueue();
        if (queue == null) {
            queue = message.getMessageProperties().getReceivedRoutingKey();
        }
        String error = truncate(cause);
        try {
            if (RabbitConfig.IMPORT_QUEUE.equals(queue)) {
                ImportMessage payload = objectMapper.readValue(message.getBody(), ImportMessage.class);
                importJobRunner.markFailed(payload.jobId(), error);
            } else if (RabbitConfig.AI_REVIEW_QUEUE.equals(queue)) {
                AiReviewMessage payload = objectMapper.readValue(message.getBody(), AiReviewMessage.class);
                reviewAiProcessor.markManualAfterFailure(payload.reviewId(), error);
            } else {
                log.error("消息重试耗尽 queue={} error={}", queue, error);
            }
        } catch (Exception ex) {
            log.error("消息失败兜底处理异常 queue={} type={}", queue, ex.getClass().getSimpleName());
        }
        throw new AmqpRejectAndDontRequeueException("消息处理失败且重试已耗尽", cause);
    }

    private String truncate(Throwable cause) {
        String message = cause == null ? null : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (message == null) {
            return "UNKNOWN";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
