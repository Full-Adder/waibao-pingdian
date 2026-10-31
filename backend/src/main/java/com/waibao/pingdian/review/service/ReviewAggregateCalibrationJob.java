package com.waibao.pingdian.review.service;

import com.waibao.pingdian.account.domain.RiskSubjectType;
import com.waibao.pingdian.account.service.RiskSignalService;
import com.waibao.pingdian.common.redis.RedisLockService;
import com.waibao.pingdian.company.service.CompanyAggregateService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * 每日全量聚合校准，由 XXL-Job 触发：与库内值比较，差异写日志与 risk_signal(AGGREGATE_DRIFT)，再重算。
 * 入口使用 Redis 分布式锁，防止多实例同时校准。
 */
@Service
public class ReviewAggregateCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(ReviewAggregateCalibrationJob.class);
    private static final Duration LOCK_TTL = Duration.ofHours(2);

    private final JdbcTemplate jdbcTemplate;
    private final CompanyAggregateService aggregateService;
    private final RiskSignalService riskSignalService;
    private final RedisLockService lockService;

    public ReviewAggregateCalibrationJob(JdbcTemplate jdbcTemplate,
                                         CompanyAggregateService aggregateService,
                                         RiskSignalService riskSignalService,
                                         RedisLockService lockService) {
        this.jdbcTemplate = jdbcTemplate;
        this.aggregateService = aggregateService;
        this.riskSignalService = riskSignalService;
        this.lockService = lockService;
    }

    @XxlJob("reviewAggregateCalibrationJob")
    public void reviewAggregateCalibrationJob() {
        RedisLockService.LockResult<Integer> result = lockService.runWithLock(
                RedisLockService.dailyKey("review-aggregate-calibration"), LOCK_TTL,
                this::calibrateAll);
        if (result.acquired()) {
            log.info("review aggregate calibrated drifted={}", result.value());
        }
    }

    /**
     * 返回检测到差异的公司数；测试可直接调用。
     */
    public int calibrateAll() {
        List<String> usccs = jdbcTemplate.query("SELECT uscc FROM company WHERE status = 'ACTIVE'",
                (rs, rowNum) -> rs.getString("uscc"));
        int drifted = 0;
        for (String uscc : usccs) {
            Expected expected = expected(uscc);
            Stored stored = stored(uscc);
            if (stored != null && differs(stored, expected)) {
                drifted++;
                log.warn("aggregate drift uscc={} stored={} expected={}", uscc, stored, expected);
                riskSignalService.record(RiskSubjectType.USER, uscc, "AGGREGATE_DRIFT", 10,
                        Duration.ofDays(7));
            }
            aggregateService.recompute(uscc);
        }
        return drifted;
    }

    private Expected expected(String uscc) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT COUNT(*) AS review_count, AVG(total_score)::numeric AS avg_score
                FROM review WHERE uscc = ? AND status = 'PUBLISHED'
                """, uscc);
        if (rows.isEmpty()) {
            return new Expected(0, null, false);
        }
        Number count = (Number) rows.get(0).get("review_count");
        int reviewCount = count == null ? 0 : count.intValue();
        Object avg = rows.get(0).get("avg_score");
        BigDecimal average = null;
        if (avg instanceof BigDecimal decimal) {
            average = decimal.setScale(1, RoundingMode.HALF_UP);
        } else if (avg instanceof Number number) {
            average = new BigDecimal(number.toString()).setScale(1, RoundingMode.HALF_UP);
        }
        return new Expected(reviewCount, average, reviewCount >= 3);
    }

    private Stored stored(String uscc) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT review_count, avg_score, score_visible FROM company WHERE uscc = ?
                """, uscc);
        if (rows.isEmpty()) {
            return null;
        }
        java.util.Map<String, Object> row = rows.get(0);
        Number count = (Number) row.get("review_count");
        Object avg = row.get("avg_score");
        BigDecimal average = null;
        if (avg instanceof BigDecimal decimal) {
            average = decimal.setScale(1, RoundingMode.HALF_UP);
        } else if (avg instanceof Number number) {
            average = new BigDecimal(number.toString()).setScale(1, RoundingMode.HALF_UP);
        }
        return new Stored(count == null ? 0 : count.intValue(), average,
                Boolean.TRUE.equals(row.get("score_visible")));
    }

    private record Expected(int reviewCount, BigDecimal avgScore, boolean scoreVisible) {
    }

    private record Stored(int reviewCount, BigDecimal avgScore, boolean scoreVisible) {
    }

    private static boolean differs(Stored stored, Expected expected) {
        if (stored.reviewCount() != expected.reviewCount()
                || stored.scoreVisible() != expected.scoreVisible()) {
            return true;
        }
        if (stored.avgScore() == null || expected.avgScore() == null) {
            return stored.avgScore() != expected.avgScore();
        }
        return stored.avgScore().compareTo(expected.avgScore()) != 0;
    }
}
