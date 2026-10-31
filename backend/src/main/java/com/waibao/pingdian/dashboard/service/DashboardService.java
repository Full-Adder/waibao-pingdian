package com.waibao.pingdian.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.redis.RedisSupport;
import com.waibao.pingdian.dashboard.web.dto.DashboardResponse;
import com.waibao.pingdian.dashboard.web.dto.DashboardWindow;
import com.waibao.pingdian.dashboard.web.dto.FunnelStage;
import com.waibao.pingdian.dashboard.web.dto.HealthRedLine;
import com.waibao.pingdian.dashboard.web.dto.MetricView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据看板指标（R18 / PRD 17.1）。
 *
 * 所有时间窗口按业务时区 Asia/Shanghai 的自然日 [from 00:00, to+1 00:00) 计算。
 * 比率指标统一返回百分比（%），举报密度返回千分比（‰）。
 * city / industry 过滤：公司相关指标与搜索指标直接过滤；用户指标按「与过滤公司存在
 * 核验或评价关联」过滤；UV 与 30 天复访率基于 visit_event，无城市/行业字段，保持全量口径。
 *
 * 缓存：Redis key {@code dashboard:{from}:{to}:{city}:{industry}}，TTL 60s；refresh=true 先删除再重算。
 * Redis 读写异常时直接降级查库，不影响接口可用性。
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(25);
    private static final String CACHE_PREFIX = "dashboard:";

    private static final double COMPLAINT_OVERTURN_THRESHOLD = 40.0;
    private static final double MANUAL_REJECT_THRESHOLD = 25.0;
    private static final double SEARCH_ZERO_THRESHOLD = 20.0;

    private static final Map<String, String> DEFINITIONS = definitions();

    private final JdbcTemplate jdbcTemplate;
    private final RedisSupport redis;
    private final ObjectMapper objectMapper;

    public DashboardService(JdbcTemplate jdbcTemplate, RedisSupport redis, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 看板查询：默认最近 7 天；refresh=true 跳过短 TTL 缓存。
     */
    public DashboardResponse dashboard(String from, String to, String city, String industry,
                                       boolean refresh) {
        Window window = resolveWindow(from, to, city, industry);
        String cacheKey = cacheKey(window);
        if (refresh) {
            deleteCache(cacheKey);
        } else {
            Optional<DashboardResponse> cached = readCache(cacheKey);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        DashboardResponse response = compute(window);
        writeCache(cacheKey, response, CACHE_TTL);
        return response;
    }

    /**
     * 每日 02:30 任务入口：计算最近 30 天全量指标并写入缓存。
     */
    public DashboardResponse computeDailySnapshot(LocalDate from, LocalDate to) {
        Window window = windowOf(from, to, null, null);
        DashboardResponse response = compute(window);
        writeCache(cacheKey(window), response, SNAPSHOT_TTL);
        return response;
    }

    // ------------------------------------------------------------------
    // 缓存（Redis，JSON 文本）
    // ------------------------------------------------------------------

    private Optional<DashboardResponse> readCache(String cacheKey) {
        try {
            Optional<String> raw = redis.find(cacheKey);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw.get(), DashboardResponse.class));
        } catch (Exception ex) {
            // 缓存损坏或 Redis 故障时降级查库，不向上抛 500。
            log.warn("看板缓存读取失败，降级查库：{}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void writeCache(String cacheKey, DashboardResponse response, Duration ttl) {
        try {
            redis.put(cacheKey, objectMapper.writeValueAsString(response), ttl);
        } catch (Exception ex) {
            log.warn("看板缓存写入失败，忽略：{}", ex.getClass().getSimpleName());
        }
    }

    private void deleteCache(String cacheKey) {
        try {
            redis.delete(cacheKey);
        } catch (Exception ex) {
            log.warn("看板缓存删除失败，忽略：{}", ex.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 指标计算
    // ------------------------------------------------------------------

    private DashboardResponse compute(Window window) {
        MetricView northstar = northstar(window);

        long registeredUsers = registeredUsers(window);
        long approvedUsers = approvedVerificationUsers(window);
        long firstReviewUsers = verificationFirstReviewUsers(window);
        long publishedReviews = publishedReviewCount(window);
        long publishingUsers = publishingUserCount(window);

        long activeCompanies = activeCompanyCount(window);
        long companiesWithReviews = companiesWithReviews(window);
        long qualifiedCompanies = companiesWithAtLeastThreeReviews(window);

        Counts aiAutoPublish = aiAutoPublishCounts(window);
        Counts manualReject = manualRejectCounts(window);
        Counts complaintOverturn = complaintOverturnCounts(window);

        long weeklyUv = weeklyUv(window);
        Counts searchZero = searchZeroCounts(window);

        long reports = reportCount(window);
        Counts revisit = revisitCounts(window);

        List<MetricView> supply = List.of(
                metric("registrationVerificationRate", "注册→核验转化率",
                        percentage(approvedUsers, registeredUsers), "%",
                        DEFINITIONS.get("registrationVerificationRate"), approvedUsers, registeredUsers),
                metric("verificationFirstReviewRate", "核验→首评转化率",
                        percentage(firstReviewUsers, approvedUsers), "%",
                        DEFINITIONS.get("verificationFirstReviewRate"), firstReviewUsers, approvedUsers),
                metric("avgPublishedReviewsPerUser", "人均已发布评价数",
                        ratio(publishedReviews, publishingUsers), "条/人",
                        DEFINITIONS.get("avgPublishedReviewsPerUser"), publishedReviews, publishingUsers));

        List<MetricView> coverage = List.of(
                metric("companyCount", "档案公司数", (double) activeCompanies, "家",
                        DEFINITIONS.get("companyCount"), null, null),
                metric("companiesWithReviews", "有评价公司数", (double) companiesWithReviews, "家",
                        DEFINITIONS.get("companiesWithReviews"), null, null),
                metric("qualifiedCompanies", "达标口碑公司数", (double) qualifiedCompanies, "家",
                        DEFINITIONS.get("qualifiedCompanies"), null, null));

        List<MetricView> quality = List.of(
                metric("aiAutoPublishRate", "AI 直放率",
                        percentage(aiAutoPublish.numerator(), aiAutoPublish.denominator()), "%",
                        DEFINITIONS.get("aiAutoPublishRate"),
                        aiAutoPublish.numerator(), aiAutoPublish.denominator()),
                metric("manualRejectRate", "人工驳回率",
                        percentage(manualReject.numerator(), manualReject.denominator()), "%",
                        DEFINITIONS.get("manualRejectRate"),
                        manualReject.numerator(), manualReject.denominator()),
                metric("complaintOverturnRate", "投诉推翻率",
                        percentage(complaintOverturn.numerator(), complaintOverturn.denominator()), "%",
                        DEFINITIONS.get("complaintOverturnRate"),
                        complaintOverturn.numerator(), complaintOverturn.denominator()));

        List<MetricView> demand = List.of(
                metric("weeklyUv", "周 UV", (double) weeklyUv, "人",
                        DEFINITIONS.get("weeklyUv"), null, null),
                metric("searchZeroResultRate", "搜索零结果率",
                        percentage(searchZero.numerator(), searchZero.denominator()), "%",
                        DEFINITIONS.get("searchZeroResultRate"),
                        searchZero.numerator(), searchZero.denominator()));

        List<MetricView> ecology = List.of(
                metric("reportDensity", "举报密度",
                        perMille(reports, publishedReviews), "‰",
                        DEFINITIONS.get("reportDensity"), reports, publishedReviews),
                metric("revisitRate30d", "30 天复访率",
                        percentage(revisit.numerator(), revisit.denominator()), "%",
                        DEFINITIONS.get("revisitRate30d"),
                        revisit.numerator(), revisit.denominator()));

        List<MetricView> all = new ArrayList<>();
        all.add(northstar);
        all.addAll(supply);
        all.addAll(coverage);
        all.addAll(quality);
        all.addAll(demand);
        all.addAll(ecology);

        List<HealthRedLine> redLines = healthRedLines(complaintOverturn, manualReject, searchZero);

        return new DashboardResponse(
                new DashboardWindow(window.from().toString(), window.to().toString(),
                        window.city(), window.industry(), BUSINESS_ZONE.getId()),
                northstar,
                supply,
                coverage,
                quality,
                demand,
                ecology,
                all,
                funnel(window),
                redLines,
                redLines,
                DEFINITIONS);
    }

    private MetricView northstar(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FILTER (WHERE review_count >= 3 AND current_count >= 1),
                       count(*) FILTER (WHERE review_count >= 3)
                FROM (
                    SELECT r.uscc,
                           count(*) AS review_count,
                           count(*) FILTER (WHERE r.verification_badge = 'CURRENT') AS current_count
                    FROM review r
                    JOIN company c ON c.uscc = r.uscc
                    WHERE r.status = 'PUBLISHED'
                      AND r.published_at >= ? AND r.published_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append(" GROUP BY r.uscc) t");
        Counts counts = counts(sql.toString(), args);
        return metric("weeklyEffectiveReputationCompanies", "周有效口碑公司数",
                (double) counts.numerator(), "家",
                DEFINITIONS.get("weeklyEffectiveReputationCompanies"),
                counts.numerator(), counts.denominator());
    }

    private long registeredUsers(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM user_account u
                WHERE u.created_at >= ? AND u.created_at < ?
                """);
        appendUserCompanyLink(sql, "u.id", window, args);
        return count(sql.toString(), args);
    }

    private long approvedVerificationUsers(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(DISTINCT uv.user_id)
                FROM user_verification uv
                JOIN company c ON c.uscc = uv.company_uscc
                WHERE uv.status = 'APPROVED'
                  AND uv.reviewed_at >= ? AND uv.reviewed_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        return count(sql.toString(), args);
    }

    private long verificationFirstReviewUsers(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM (
                    SELECT uv.user_id
                    FROM user_verification uv
                    JOIN company c ON c.uscc = uv.company_uscc
                    WHERE uv.status = 'APPROVED'
                      AND uv.reviewed_at >= ? AND uv.reviewed_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append("""
                      AND EXISTS (
                          SELECT 1 FROM review r
                          JOIN company rc ON rc.uscc = r.uscc
                          WHERE r.user_id = uv.user_id
                            AND r.status = 'PUBLISHED'
                            AND r.published_at >= ? AND r.published_at < ?
                """);
        appendCompanyFilter(sql, "rc", window.city(), window.industry());
        args.add(startArg(window));
        args.add(endArg(window));
        addCompanyArgs(args, window.city(), window.industry());
        sql.append(" ) GROUP BY uv.user_id) t");
        return count(sql.toString(), args);
    }

    private long publishedReviewCount(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM review r
                JOIN company c ON c.uscc = r.uscc
                WHERE r.status = 'PUBLISHED'
                  AND r.published_at >= ? AND r.published_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        return count(sql.toString(), args);
    }

    private long publishingUserCount(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(DISTINCT r.user_id) FROM review r
                JOIN company c ON c.uscc = r.uscc
                WHERE r.status = 'PUBLISHED'
                  AND r.published_at >= ? AND r.published_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        return count(sql.toString(), args);
    }

    private long activeCompanyCount(Window window) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM company c WHERE c.status = 'ACTIVE'");
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        return count(sql.toString(), args);
    }

    private long companiesWithReviews(Window window) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM (");
        String inner = publishedCompanyGroupSql(window, args, false);
        sql.append(inner).append(") t");
        return count(sql.toString(), args);
    }

    private long companiesWithAtLeastThreeReviews(Window window) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM (");
        String inner = publishedCompanyGroupSql(window, args, true);
        sql.append(inner).append(") t");
        return count(sql.toString(), args);
    }

    private String publishedCompanyGroupSql(Window window, List<Object> args, boolean atLeastThree) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.uscc FROM review r
                JOIN company c ON c.uscc = r.uscc
                WHERE r.status = 'PUBLISHED'
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append(" GROUP BY r.uscc");
        if (atLeastThree) {
            sql.append(" HAVING count(*) >= 3");
        }
        return sql.toString();
    }

    private Counts aiAutoPublishCounts(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                WITH submits AS (
                    SELECT a.review_id, min(a.created_at) AS submitted_at
                    FROM review_audit a
                    JOIN review r ON r.id = a.review_id
                    JOIN company c ON c.uscc = r.uscc
                    WHERE a.action = 'SUBMIT'
                      AND a.created_at >= ? AND a.created_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append("""
                    GROUP BY a.review_id
                )
                SELECT
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM review_audit p
                        WHERE p.review_id = s.review_id
                          AND p.to_status = 'PUBLISHED'
                          AND p.created_at >= s.submitted_at
                          AND NOT EXISTS (
                              SELECT 1 FROM review_audit m
                              WHERE m.review_id = s.review_id
                                AND m.to_status = 'PENDING_MANUAL'
                                AND m.created_at >= s.submitted_at
                                AND m.created_at < p.created_at
                          )
                    )),
                    count(*)
                FROM submits s
                """);
        return counts(sql.toString(), args);
    }

    private Counts manualRejectCounts(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FILTER (WHERE a.action = 'REJECT'), count(*)
                FROM review_audit a
                JOIN review r ON r.id = a.review_id
                JOIN company c ON c.uscc = r.uscc
                WHERE a.actor_type = 'ADMIN'
                  AND a.action IN ('APPROVE', 'FOLD', 'REJECT')
                  AND a.created_at >= ? AND a.created_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        return counts(sql.toString(), args);
    }

    private Counts complaintOverturnCounts(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FILTER (WHERE co.status = 'RULED_USER_WIN'), count(*)
                FROM complaint co
                JOIN review r ON r.id = co.review_id
                JOIN company c ON c.uscc = r.uscc
                WHERE co.status IN ('RULED_USER_WIN', 'RULED_COMPANY_WIN', 'RULED_INSUFFICIENT')
                  AND co.created_at >= ? AND co.created_at < ?
                """);
        appendCompanyFilter(sql, "c", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        return counts(sql.toString(), args);
    }

    private long weeklyUv(Window window) {
        return count("""
                        SELECT count(DISTINCT visitor_id) FROM visit_event
                        WHERE visitor_id IS NOT NULL
                          AND created_at >= ? AND created_at < ?
                        """,
                List.of(startArg(window), endArg(window)));
    }

    private Counts searchZeroCounts(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FILTER (WHERE result_count = 0), count(*)
                FROM search_log
                WHERE created_at >= ? AND created_at < ?
                """);
        if (window.city() != null) {
            sql.append(" AND city = ?");
            args.add(window.city());
        }
        if (window.industry() != null) {
            sql.append(" AND industry = ?");
            args.add(window.industry());
        }
        return counts(sql.toString(), args);
    }

    private long reportCount(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM report rp
                WHERE rp.created_at >= ? AND rp.created_at < ?
                """);
        if (window.city() != null || window.industry() != null) {
            sql.append("""
                     AND (
                         (rp.target_type = 'COMPANY' AND rp.target_id::text IN (
                             SELECT cc.uscc FROM company cc WHERE 1 = 1
                    """);
            appendCompanyFilter(sql, "cc", window.city(), window.industry());
            addCompanyArgs(args, window.city(), window.industry());
            sql.append("""
                         ))
                         OR
                         (rp.target_type = 'REVIEW' AND EXISTS (
                             SELECT 1 FROM review rr
                             JOIN company rc ON rc.uscc = rr.uscc
                             WHERE rr.id = rp.target_id
                    """);
            appendCompanyFilter(sql, "rc", window.city(), window.industry());
            addCompanyArgs(args, window.city(), window.industry());
            sql.append(" ))");
        }
        if (window.city() != null || window.industry() != null) {
            sql.append(" )");
        }
        return count(sql.toString(), args);
    }

    private Counts revisitCounts(Window window) {
        Instant revisitStart = window.endExclusive().minus(30, ChronoUnit.DAYS);
        return counts("""
                        SELECT count(*) FILTER (WHERE day_count >= 2), count(*)
                        FROM (
                            SELECT visitor_id,
                                   count(DISTINCT (created_at AT TIME ZONE 'Asia/Shanghai')::date)
                                       AS day_count
                            FROM visit_event
                            WHERE visitor_id IS NOT NULL
                              AND created_at >= ? AND created_at < ?
                            GROUP BY visitor_id
                        ) t
                        """,
                List.of(Timestamp.from(revisitStart), Timestamp.from(window.endExclusive())));
    }

    private List<FunnelStage> funnel(Window window) {
        List<Object> args = new ArrayList<>();
        args.add(startArg(window));
        args.add(endArg(window));
        StringBuilder sql = new StringBuilder("""
                WITH cohort AS (
                    SELECT cu.id, cu.realname_status
                    FROM user_account cu
                    WHERE cu.created_at >= ? AND cu.created_at < ?
                """);
        appendUserCompanyLink(sql, "cu.id", window, args);
        sql.append("""
                )
                SELECT
                    count(*),
                    count(*) FILTER (WHERE cu.realname_status = 'VERIFIED'),
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM user_verification uv
                        JOIN company vc ON vc.uscc = uv.company_uscc
                        WHERE uv.user_id = cu.id AND uv.status = 'APPROVED'
                """);
        appendCompanyFilter(sql, "vc", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append("""
                    )),
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM review r
                        JOIN company rc ON rc.uscc = r.uscc
                        WHERE r.user_id = cu.id AND r.status = 'PUBLISHED'
                """);
        appendCompanyFilter(sql, "rc", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append(" ))\n FROM cohort cu");
        long[] stages = funnelCounts(sql.toString(), args);

        return List.of(
                new FunnelStage("注册", stages[0], null),
                new FunnelStage("实名", stages[1], conversion(stages[1], stages[0])),
                new FunnelStage("核验", stages[2], conversion(stages[2], stages[1])),
                new FunnelStage("首评", stages[3], conversion(stages[3], stages[2])));
    }

    private List<HealthRedLine> healthRedLines(Counts complaint, Counts manual, Counts search) {
        double complaintRaw = rawPercentage(complaint.numerator(), complaint.denominator());
        double manualRaw = rawPercentage(manual.numerator(), manual.denominator());
        double searchRaw = rawPercentage(search.numerator(), search.denominator());

        return List.of(
                redLine("complaintOverturnRate", "投诉推翻率", complaintRaw,
                        COMPLAINT_OVERTURN_THRESHOLD, "<",
                        complaint.denominator() > 0 && complaintRaw < COMPLAINT_OVERTURN_THRESHOLD,
                        "收紧投诉准入 + 冻结涉事企业的投诉资格"),
                redLine("manualRejectRate", "人工驳回率", manualRaw,
                        MANUAL_REJECT_THRESHOLD, ">",
                        manual.denominator() > 0 && manualRaw > MANUAL_REJECT_THRESHOLD,
                        "改提示词与填写引导，并抽样复盘误判"),
                redLine("searchZeroResultRate", "搜索零结果率", searchRaw,
                        SEARCH_ZERO_THRESHOLD, ">",
                        search.denominator() > 0 && searchRaw > SEARCH_ZERO_THRESHOLD,
                        "暂停拉新，先补档案数据"));
    }

    private static HealthRedLine redLine(String key, String label, double rawValue,
                                         double threshold, String comparison,
                                         boolean triggered, String suggestion) {
        return new HealthRedLine(key, label, round(rawValue), threshold, comparison,
                triggered, triggered, suggestion);
    }

    // ------------------------------------------------------------------
    // 窗口、缓存与 SQL 工具
    // ------------------------------------------------------------------

    private Window resolveWindow(String from, String to, String city, String industry) {
        LocalDate toDate;
        try {
            toDate = to == null || to.isBlank() ? LocalDate.now(BUSINESS_ZONE) : LocalDate.parse(to.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "to 日期格式不正确，应为 YYYY-MM-DD");
        }
        LocalDate fromDate;
        try {
            fromDate = from == null || from.isBlank() ? toDate.minusDays(6) : LocalDate.parse(from.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "from 日期格式不正确，应为 YYYY-MM-DD");
        }
        if (fromDate.isAfter(toDate)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "from 不能晚于 to");
        }
        return windowOf(fromDate, toDate, blankToNull(city), blankToNull(industry));
    }

    private Window windowOf(LocalDate from, LocalDate to, String city, String industry) {
        Instant start = from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant endExclusive = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        return new Window(from, to, start, endExclusive, city, industry);
    }

    private String cacheKey(Window window) {
        return redis.key(CACHE_PREFIX + window.from() + ":" + window.to() + ":"
                + nullSafe(window.city()) + ":" + nullSafe(window.industry()));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Timestamp startArg(Window window) {
        return Timestamp.from(window.start());
    }

    private static Timestamp endArg(Window window) {
        return Timestamp.from(window.endExclusive());
    }

    private static void appendCompanyFilter(StringBuilder sql, String alias,
                                             String city, String industry) {
        if (city != null) {
            sql.append(" AND ").append(alias).append(".city = ?");
        }
        if (industry != null) {
            sql.append(" AND ").append(alias).append(".industry = ?");
        }
    }

    private static void addCompanyArgs(List<Object> args, String city, String industry) {
        if (city != null) {
            args.add(city);
        }
        if (industry != null) {
            args.add(industry);
        }
    }

    /**
     * 用户维度过滤：存在过滤公司时，要求用户与过滤公司在核验或评价上有关联。
     */
    private static void appendUserCompanyLink(StringBuilder sql, String userExpr,
                                              Window window, List<Object> args) {
        if (window.city() == null && window.industry() == null) {
            return;
        }
        sql.append("""
                 AND (
                     EXISTS (
                         SELECT 1 FROM user_verification lv
                         JOIN company lc ON lc.uscc = lv.company_uscc
                         WHERE lv.user_id = 
                """).append(userExpr);
        appendCompanyFilter(sql, "lc", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append("""
                     )
                     OR EXISTS (
                         SELECT 1 FROM review lr
                         JOIN company lc2 ON lc2.uscc = lr.uscc
                         WHERE lr.user_id = 
                """).append(userExpr);
        appendCompanyFilter(sql, "lc2", window.city(), window.industry());
        addCompanyArgs(args, window.city(), window.industry());
        sql.append(" ) )");
    }

    private long count(String sql, List<Object> args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private Counts counts(String sql, List<Object> args) {
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return new Counts(0, 0);
            }
            return new Counts(rs.getLong(1), rs.getLong(2));
        }, args.toArray());
    }

    private long[] funnelCounts(String sql, List<Object> args) {
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return new long[]{0, 0, 0, 0};
            }
            return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)};
        }, args.toArray());
    }

    // ------------------------------------------------------------------
    // 数值与口径
    // ------------------------------------------------------------------

    private static MetricView metric(String key, String label, double value, String unit,
                                     String definition, Long numerator, Long denominator) {
        return new MetricView(key, label, round(value), unit, definition, definition,
                numerator, denominator);
    }

    private static double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator * 100.0 / denominator);
    }

    private static double perMille(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator * 1000.0 / denominator);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator * 1.0 / denominator);
    }

    private static double rawPercentage(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private static Double conversion(long current, long previous) {
        return previous == 0 ? null : round(current * 100.0 / previous);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, String> definitions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("weeklyEffectiveReputationCompanies",
                "周有效口碑公司数 = 窗口内 published_at 落在窗口、状态为 PUBLISHED 的评价中，"
                        + "公司满足「≥3 条 PUBLISHED」且「其中 ≥1 条 verification_badge=CURRENT」的公司数；"
                        + "分母为窗口内 ≥3 条 PUBLISHED 的公司数。");
        map.put("registrationVerificationRate",
                "注册→核验转化率 = 窗口内完成在职佐证核验（APPROVED，按 reviewed_at）的去重用户数 / "
                        + "窗口内注册用户数（按 user_account.created_at）。");
        map.put("verificationFirstReviewRate",
                "核验→首评转化率 = 窗口内核验通过且发布 ≥1 条 PUBLISHED 评价的去重用户数 / "
                        + "窗口内核验通过用户数。口径差异：PRD 未限定首评时间，本实现按 published_at "
                        + "落在同一窗口统计。");
        map.put("avgPublishedReviewsPerUser",
                "人均已发布评价数 = 窗口内 PUBLISHED 评价数（published_at 在窗口）/ "
                        + "窗口内发评去重用户数。");
        map.put("companyCount", "档案公司数 = status=ACTIVE 的公司数（全量，不限定评价时间）。");
        map.put("companiesWithReviews", "有评价公司数 = 至少有 1 条 PUBLISHED 评价的公司数（全量）。");
        map.put("qualifiedCompanies", "达标口碑公司数 = 至少有 3 条 PUBLISHED 评价的公司数（全量）。");
        map.put("aiAutoPublishRate",
                "AI 直放率 = 提交后未进人工、首个处置状态即为 PUBLISHED 的评价数 / 窗口内总提交数；"
                        + "以 review_audit action=SUBMIT 与 to_status=PENDING_MANUAL/PUBLISHED 迁移判断。");
        map.put("manualRejectRate",
                "人工驳回率 = 人工 REJECT 数 / 人工处理数（APPROVE + FOLD + REJECT），"
                        + "actor_type=ADMIN，按 review_audit.created_at 窗口统计。");
        map.put("complaintOverturnRate",
                "投诉推翻率 = 用户胜诉投诉数（RULED_USER_WIN）/ 已裁定投诉总数"
                        + "（RULED_USER_WIN + RULED_COMPANY_WIN + RULED_INSUFFICIENT）。"
                        + "口径差异：PRD 17.1 写作「用户胜诉投诉 / 总投诉」，本实现仅统计已裁定投诉；"
                        + "complaint 表无裁定时间字段，窗口按 created_at 统计。");
        map.put("weeklyUv",
                "周 UV = 窗口内 visit_event 去重 visitor_id 数；不采集 IP 与任何个人明文。"
                        + "visit_event 无 city/industry 字段，城市/行业过滤不作用于该指标。");
        map.put("searchZeroResultRate",
                "搜索零结果率 = result_count=0 的搜索数 / 搜索总数，"
                        + "按 search_log.created_at 落在窗口，并按 city/industry 过滤。");
        map.put("reportDensity",
                "举报密度（千分比）= 窗口内举报数 / 窗口内 PUBLISHED 评价数 × 1000。");
        map.put("revisitRate30d",
                "30 天复访率 = 以窗口 to 为终点的最近 30 天内，visit_event 出现 ≥2 个不同自然日"
                        + "（Asia/Shanghai）的 visitor_id 数 / 同期去重 visitor_id 数。");
        return Map.copyOf(map);
    }

    private record Window(LocalDate from, LocalDate to, Instant start, Instant endExclusive,
                          String city, String industry) {
    }

    private record Counts(long numerator, long denominator) {
    }
}
