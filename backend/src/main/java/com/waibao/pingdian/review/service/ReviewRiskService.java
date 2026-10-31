package com.waibao.pingdian.review.service;

import com.waibao.pingdian.account.domain.RiskSubjectType;
import com.waibao.pingdian.account.service.RiskSignalService;
import com.waibao.pingdian.review.config.ReviewProperties;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 发评风险信号与复制粘贴检测（R9）。
 */
@Service
public class ReviewRiskService {

    private static final Set<ReviewStatus> COPY_IGNORED_STATUSES = Set.of(
            ReviewStatus.DRAFT, ReviewStatus.WITHDRAWN, ReviewStatus.REJECTED, ReviewStatus.REMOVED);

    private final ReviewRepository reviewRepository;
    private final RiskSignalService riskSignalService;
    private final ReviewProperties reviewProperties;
    private final JdbcTemplate jdbcTemplate;
    private final int accountAgeGateHours;

    public ReviewRiskService(ReviewRepository reviewRepository,
                             RiskSignalService riskSignalService,
                             ReviewProperties reviewProperties,
                             JdbcTemplate jdbcTemplate,
                             @Value("${account.age-gate-hours:0}") int accountAgeGateHours) {
        this.reviewRepository = reviewRepository;
        this.riskSignalService = riskSignalService;
        this.reviewProperties = reviewProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.accountAgeGateHours = accountAgeGateHours;
    }

    /**
     * 记录 IP / 设备 / 用户信号，返回本次命中的风险项（命中即强制转人工，不直接拒绝）。
     */
    public List<String> assess(UUID userId, String ip, String device, Instant accountCreatedAt) {
        ReviewProperties.Risk risk = reviewProperties.getRisk();
        Instant windowStart = Instant.now().minus(risk.getBurstWindowHours(), ChronoUnit.HOURS);
        Duration ttl = Duration.ofHours(risk.getSignalTtlHours());

        int ipPrior = countSignals("IP", ip, windowStart);
        int devicePrior = countSignals("DEVICE", device, windowStart);

        riskSignalService.record(RiskSubjectType.IP, ip, "REVIEW_SUBMIT", 1, ttl);
        if (device != null && !device.isBlank()) {
            riskSignalService.record(RiskSubjectType.DEVICE, device, "REVIEW_SUBMIT", 1, ttl);
        }
        riskSignalService.record(RiskSubjectType.USER, userId.toString(), "REVIEW_SUBMIT", 1, ttl);

        List<String> signals = new ArrayList<>();
        if ((ip != null && ipPrior + 1 >= risk.getBurstThreshold())
                || (device != null && !device.isBlank() && devicePrior + 1 >= risk.getBurstThreshold())) {
            signals.add("SHORT_WINDOW_BURST");
            String subject = ipPrior + 1 >= risk.getBurstThreshold() ? ip : device;
            riskSignalService.record(RiskSubjectType.IP, subject, "REVIEW_BURST", 10, ttl);
        }
        if (accountAgeGateHours > 0 && accountCreatedAt != null
                && accountCreatedAt.isAfter(Instant.now().minus(accountAgeGateHours, ChronoUnit.HOURS))) {
            signals.add("NEW_ACCOUNT");
            riskSignalService.record(RiskSubjectType.USER, userId.toString(), "NEW_ACCOUNT", 5, ttl);
        }
        return signals;
    }

    /**
     * 同一用户归一化正文出现在 ≥N 家不同公司 → 命中复制检测。
     */
    public boolean detectCopy(UUID userId, String content) {
        String normalized = normalize(content);
        if (normalized.isEmpty()) {
            return false;
        }
        Set<String> companies = new HashSet<>();
        for (Review review : reviewRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (COPY_IGNORED_STATUSES.contains(review.getStatus())) {
                continue;
            }
            if (normalized.equals(normalize(review.getContent()))) {
                companies.add(review.getUscc());
            }
        }
        return companies.size() >= reviewProperties.getCopyDetectCompanies();
    }

    private int countSignals(String subjectType, String subjectValue, Instant since) {
        if (subjectValue == null || subjectValue.isBlank()) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM risk_signal
                        WHERE subject_type = ? AND subject_value = ? AND signal_type = 'REVIEW_SUBMIT'
                          AND created_at >= ?
                        """,
                Integer.class, subjectType, subjectValue, java.sql.Timestamp.from(since));
        return count == null ? 0 : count;
    }

    static String normalize(String content) {
        if (content == null) {
            return "";
        }
        return content.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{P}\\p{Z}\\s]", "");
    }
}
