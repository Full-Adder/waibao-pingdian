package com.waibao.pingdian.ranking.service;

import com.waibao.pingdian.common.redis.RedisLockService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 每日 03:00（Asia/Shanghai）重算所有非空城市+行业并写入 ranking_snapshot，由 XXL-Job 触发。
 * 入口使用 Redis 分布式锁防止多实例重复重算。
 */
@Service
public class RankingSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(RankingSnapshotJob.class);
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    private final RankingService rankingService;
    private final RedisLockService lockService;

    public RankingSnapshotJob(RankingService rankingService, RedisLockService lockService) {
        this.rankingService = rankingService;
        this.lockService = lockService;
    }

    @XxlJob("rankingSnapshotJob")
    public void rankingSnapshotJob() {
        RedisLockService.LockResult<Integer> result = lockService.runWithLock(
                RedisLockService.dailyKey("ranking-snapshot"), LOCK_TTL,
                rankingService::recomputeAllSnapshots);
        if (result.acquired()) {
            log.info("ranking snapshots recomputed count={}", result.value());
        }
    }
}
