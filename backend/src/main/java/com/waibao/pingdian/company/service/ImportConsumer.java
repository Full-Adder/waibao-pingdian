package com.waibao.pingdian.company.service;

import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.messages.ImportMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 工商导入消费者：JobRunner 内部处理幂等；异常交给 Spring AMQP 重试，
 * 重试耗尽后由 MqFailureRecoverer 将任务置 FAILED。
 */
@Component
public class ImportConsumer {

    private final ImportJobRunner jobRunner;

    public ImportConsumer(ImportJobRunner jobRunner) {
        this.jobRunner = jobRunner;
    }

    @RabbitListener(queues = RabbitConfig.IMPORT_QUEUE)
    public void onMessage(ImportMessage message) {
        jobRunner.runJob(message.jobId());
    }
}
