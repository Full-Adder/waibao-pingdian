package com.waibao.pingdian.mq;

import com.waibao.pingdian.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 消息发布入口：有事务时登记 afterCommit 再发，无事务立即发。
 * 发布失败只记录日志，不影响主事务；消息体只放业务 id。
 */
@Component
public class MqEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public MqEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 通过业务交换机发布事件（routing key 见 RabbitConfig）。
     */
    public void publishAfterCommit(String routingKey, Object payload) {
        runAfterCommit(() -> rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, payload));
    }

    /**
     * 发布到通知延迟队列（默认交换机直投队列名），到期后由死信路由回主队列。
     */
    public void publishDelayAfterCommit(Object payload) {
        runAfterCommit(() -> rabbitTemplate.convertAndSend("", RabbitConfig.NOTIFICATION_DELAY_QUEUE, payload));
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendSafely(action);
                }
            });
        } else {
            sendSafely(action);
        }
    }

    private void sendSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            // 消息发送失败不回滚主事务，由兜底任务或人工补偿。
            log.warn("MQ 消息发布失败: {}", ex.getClass().getSimpleName());
        }
    }
}
