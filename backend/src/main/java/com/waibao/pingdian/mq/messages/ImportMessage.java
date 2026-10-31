package com.waibao.pingdian.mq.messages;

import java.util.UUID;

/**
 * 工商导入任务消息：只携带 job id，消费端自行加载任务。
 */
public record ImportMessage(UUID jobId) {
}
