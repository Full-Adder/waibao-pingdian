package com.waibao.pingdian.retention.service;

import com.waibao.pingdian.common.redis.RedisLockService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 材料保留任务：每日 03:10（Asia/Shanghai）由 XXL-Job 触发一次。入口使用 Redis 分布式锁防止多实例重复执行。
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final Duration LOCK_TTL = Duration.ofHours(1);

    private final RetentionService retentionService;
    private final RedisLockService lockService;

    public RetentionJob(RetentionService retentionService, RedisLockService lockService) {
        this.retentionService = retentionService;
        this.lockService = lockService;
    }

    @XxlJob("retentionJob")
    public void retentionJob() {
        RedisLockService.LockResult<RetentionResult> result = lockService.runWithLock(
                RedisLockService.dailyKey("retention"), LOCK_TTL, retentionService::run);
        if (result.acquired()) {
            log.info("retention job finished result={}", result.value());
        }
    }
}
