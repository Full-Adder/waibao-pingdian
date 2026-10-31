package com.waibao.pingdian.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息驱动开关：true 走 RabbitMQ，false 退回进程内同步执行（集成测试固定为 false）。
 */
@ConfigurationProperties(prefix = "app.mq")
public class MqProperties {

    private boolean async = true;

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }
}
