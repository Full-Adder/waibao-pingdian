package com.waibao.pingdian.review.service;

import com.waibao.pingdian.common.redis.RedisLockService;
import com.waibao.pingdian.review.config.ReviewProperties;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 7 天未提交草稿清理，每日由 XXL-Job 触发一次。入口使用 Redis 分布式锁防止多实例重复清理。
 */
@Service
public class ReviewDraftCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ReviewDraftCleanupJob.class);
    private static final Duration LOCK_TTL = Duration.ofHours(1);

    private final JdbcTemplate jdbcTemplate;
    private final ReviewProperties reviewProperties;
    private final RedisLockService lockService;

    public ReviewDraftCleanupJob(JdbcTemplate jdbcTemplate, ReviewProperties reviewProperties,
                                 RedisLockService lockService) {
        this.jdbcTemplate = jdbcTemplate;
        this.reviewProperties = reviewProperties;
        this.lockService = lockService;
    }

    @XxlJob("reviewDraftCleanupJob")
    public void reviewDraftCleanupJob() {
        lockService.runWithLock(RedisLockService.dailyKey("review-draft-cleanup"), LOCK_TTL,
                this::cleanupDrafts);
    }

    public int cleanupDrafts() {
        int deleted = jdbcTemplate.update("""
                DELETE FROM review
                WHERE status = 'DRAFT'
                  AND updated_at < now() - make_interval(days => ?)
                """, reviewProperties.getDraftTtlDays());
        if (deleted > 0) {
            log.info("cleaned {} expired review drafts", deleted);
        }
        return deleted;
    }
}
