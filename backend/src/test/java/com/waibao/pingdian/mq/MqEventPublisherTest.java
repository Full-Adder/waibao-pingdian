package com.waibao.pingdian.mq;

import com.waibao.pingdian.mq.messages.ImportMessage;
import com.waibao.pingdian.mq.messages.NotificationMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MqEventPublisher：事务内 afterCommit 才发送，无事务立即发送，发送失败不抛出。
 */
class MqEventPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MqEventPublisher publisher = new MqEventPublisher(rabbitTemplate);

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesImmediatelyWhenNoTransactionIsActive() {
        publisher.publishAfterCommit("import.run", new ImportMessage(UUID.randomUUID()));

        verify(rabbitTemplate).convertAndSend(eq("waibao.events"), eq("import.run"), any(Object.class));
    }

    @Test
    void defersPublishUntilAfterCommitWhenTransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishAfterCommit("import.run", new ImportMessage(UUID.randomUUID()));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(rabbitTemplate).convertAndSend(eq("waibao.events"), eq("import.run"), any(Object.class));
    }

    @Test
    void publishesDelayMessageDirectlyToDelayQueue() {
        publisher.publishDelayAfterCommit(new NotificationMessage(UUID.randomUUID(), 1));

        verify(rabbitTemplate).convertAndSend(eq(""), eq("waibao.notification.delay.queue"),
                any(Object.class));
    }

    @Test
    void publishFailureDoesNotPropagate() {
        doThrow(new IllegalStateException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> publisher.publishAfterCommit("import.run", new ImportMessage(UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}
