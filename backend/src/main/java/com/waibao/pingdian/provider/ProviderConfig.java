package com.waibao.pingdian.provider;

import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.config.StorageProperties;
import com.waibao.pingdian.provider.mock.LocalEncryptedObjectStorageProvider;
import com.waibao.pingdian.provider.mock.MockAiModerationProvider;
import com.waibao.pingdian.provider.mock.MockBusinessDataProvider;
import com.waibao.pingdian.provider.mock.MockRealnameProvider;
import com.waibao.pingdian.provider.mock.MockSmsProvider;
import com.waibao.pingdian.provider.real.RealAiModerationProvider;
import com.waibao.pingdian.provider.real.RealBusinessDataProvider;
import com.waibao.pingdian.provider.real.RealObjectStorageProvider;
import com.waibao.pingdian.provider.real.RealRealnameProvider;
import com.waibao.pingdian.provider.real.RealSmsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * providers.mode=mock（默认）使用本地 Mock；real 使用占位实现。
 * 业务层只依赖接口，切换模式不改业务代码。
 */
@Configuration
public class ProviderConfig {

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "mock", matchIfMissing = true)
    public SmsProvider mockSmsProvider(ProvidersProperties properties) {
        return new MockSmsProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "mock", matchIfMissing = true)
    public RealnameProvider mockRealnameProvider(ProvidersProperties properties) {
        return new MockRealnameProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "mock", matchIfMissing = true)
    public AiModerationProvider mockAiModerationProvider(ProvidersProperties properties) {
        return new MockAiModerationProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "mock", matchIfMissing = true)
    public BusinessDataProvider mockBusinessDataProvider(ProvidersProperties properties) {
        return new MockBusinessDataProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "mock", matchIfMissing = true)
    public ObjectStorageProvider localObjectStorageProvider(StorageProperties properties) {
        return new LocalEncryptedObjectStorageProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "real")
    public SmsProvider realSmsProvider() {
        return new RealSmsProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "real")
    public RealnameProvider realRealnameProvider() {
        return new RealRealnameProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "real")
    public AiModerationProvider realAiModerationProvider() {
        return new RealAiModerationProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "real")
    public BusinessDataProvider realBusinessDataProvider() {
        return new RealBusinessDataProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "providers", name = "mode", havingValue = "real")
    public ObjectStorageProvider realObjectStorageProvider() {
        return new RealObjectStorageProvider();
    }
}
