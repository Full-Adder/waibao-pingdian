package com.waibao.pingdian.notification.service;

import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.messages.NotificationMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 通知消费者：延迟队列到期后同样从 notification.send 回到本队列。
 * 业务失败由 NotificationSender 落库，不抛出异常，避免无限重投。
 */
@Component
public class NotificationConsumer {

    private final NotificationSender notificationSender;

    public NotificationConsumer(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    @RabbitListener(queues = RabbitConfig.NOTIFICATION_QUEUE)
    public void onMessage(NotificationMessage message) {
        notificationSender.deliver(message.notificationId(), message.retryCount());
    }
}
