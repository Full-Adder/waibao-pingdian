package com.waibao.pingdian.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 应用侧配置：统一 key 前缀，避免开发/测试/生产环境串用同一份 Redis 数据。
 */
@ConfigurationProperties(prefix = "app.redis")
public class AppRedisProperties {

    private String keyPrefix = "waibao:";

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
