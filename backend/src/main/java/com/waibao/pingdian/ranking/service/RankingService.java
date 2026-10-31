package com.waibao.pingdian.ranking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.redis.RedisSupport;
import com.waibao.pingdian.company.domain.Company;
import com.waibao.pingdian.company.domain.CompanyStatus;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.ranking.domain.CompanyMetrics;
import com.waibao.pingdian.ranking.domain.RankingType;
import com.waibao.pingdian.ranking.web.dto.MetricDefinitions;
import com.waibao.pingdian.ranking.web.dto.RankingItemView;
import com.waibao.pingdian.ranking.web.dto.RankingPageView;
import com.waibao.pingdian.review.config.RankingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 双榜计算与快照（R13）。
 * 推荐榜：PUBLISHED 样本 ≥3，加权分达到同城市+行业分位阈值；
 * 待改善名单：PUBLISHED + DISPUTED 公开样本 ≥3，低分集中度 ≥40% 或争议率 ≥30%。
 *
 * 查询结果缓存到 Redis：{@code ranking:{city}:{industry}:{type}:{limit}}，TTL 300s；
 * 每日快照任务完成后按前缀清理，Redis 故障时直接查库降级。
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    public static final double IMPROVE_LOW_THRESHOLD = 0.40;
    public static final double IMPROVE_DISPUTE_THRESHOLD = 0.30;
    public static final double IMPROVE_LOW_WEIGHT = 0.6;
    public static final double IMPROVE_DISPUTE_WEIGHT = 0.4;
    private static final double EPSILON = 1e-9;
    /** 榜单文本禁用定性词（R13）。 */
    private static final List<String> FORBIDDEN_WORDS = List.of("黑店", "骗子", "黑心");
    private static final Duration CACHE_TTL = Duration.ofSeconds(300);
    private static final String CACHE_PREFIX = "ranking:";

    private static final MetricDefinitions METRIC_DEFINITIONS = new MetricDefinitions(
            "公开样本集合 = 当前 PUBLISHED + DISPUTED（争议中仍公开可见）",
            "加权分 = 各 PUBLISHED 评价权重之和 / PUBLISHED 样本数；单条权重 = 核验徽标权重"
                    + "（在职中 1.0 / 曾在职 0.8）+ 有佐证 0.2 + 时间衰减（12 个月内 1.0，否则 0.5）"
                    + "；复制检测命中时该条权重 ×0.5",
            "低分集中度 = 公开样本集合（PUBLISHED + DISPUTED）中 total_score 为 1 或 2 的评价数 / 集合总数",
            "争议率 = 公开样本集合（PUBLISHED + DISPUTED）中 ever_disputed=true 的评价数 / 集合总数");

    private final CompanyRepository companyRepository;
    private final RankingMetricsService metricsService;
    private final RankingProperties rankingProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RedisSupport redis;

    public RankingService(CompanyRepository companyRepository,
                          RankingMetricsService metricsService,
                          RankingProperties rankingProperties,
                          JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper,
                          RedisSupport redis) {
        this.companyRepository = companyRepository;
        this.metricsService = metricsService;
        this.rankingProperties = rankingProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public RankingPageView rank(String city, String industry, String rawType,
                                String date, int limit) {
        requireGroup(city, industry);
        RankingType type = parseType(rawType);
        if (date != null && !date.isBlank()) {
            return history(city, industry, type, date);
        }
        int safeLimit = safeLimit(limit);
        String cacheKey = cacheKey(city, industry, type, safeLimit);
        Optional<RankingPageView> cached = readCache(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        RankingPageView page = computePage(city, industry, type, safeLimit);
        writeCache(cacheKey, page);
        return page;
    }

    public RankingPageView history(String city, String industry, String rawType, String rawDate) {
        requireGroup(city, industry);
        return history(city, industry, parseType(rawType), rawDate);
    }

    private RankingPageView history(String city, String industry, RankingType type, String rawDate) {
        LocalDate date = parseDate(rawDate);
        List<SnapshotRow> rows = jdbcTemplate.query("""
                        SELECT algorithm_version, params_json, payload_json, computed_at
                        FROM ranking_snapshot
                        WHERE city = ? AND industry = ? AND ranking_type = ?
                          AND (computed_at AT TIME ZONE 'Asia/Shanghai')::date = ?
                        ORDER BY computed_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new SnapshotRow(
                        rs.getString("algorithm_version"),
                        rs.getString("params_json"),
                        rs.getString("payload_json"),
                        rs.getTimestamp("computed_at").toInstant()),
                city, industry, type.name(), date);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该日期暂无榜单快照");
        }
        SnapshotRow row = rows.get(0);
        try {
            JsonNode payload = objectMapper.readTree(row.payloadJson());
            List<RankingItemView> items = objectMapper.convertValue(
                    payload.path("items"), new TypeReference<List<RankingItemView>>() {
                    });
            Double threshold = payload.hasNonNull("threshold")
                    ? payload.path("threshold").asDouble() : null;
            Map<String, Object> params = objectMapper.readValue(
                    row.paramsJson() == null ? "{}" : row.paramsJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
            return new RankingPageView(city, industry, type, row.algorithmVersion(),
                    row.computedAt(), threshold, params, METRIC_DEFINITIONS,
                    1, items.size(), items.size(), items);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "榜单快照解析失败");
        }
    }

    public RankingPageView computePage(String city, String industry, RankingType type, int limit) {
        List<Company> companies = companyRepository
                .findByCityAndIndustryAndStatusOrderByUsccAsc(city, industry, CompanyStatus.ACTIVE);
        List<CompanyMetrics> metrics = new ArrayList<>();
        for (Company company : companies) {
            metrics.add(metricsService.compute(company.getUscc(), company.getName()));
        }

        Double threshold = null;
        List<CompanyMetrics> selected;
        if (type == RankingType.RECOMMEND) {
            List<CompanyMetrics> eligible = metrics.stream()
                    .filter(m -> m.recommendEligible(rankingProperties.getMinSample()))
                    .toList();
            threshold = percentileThreshold(eligible);
            double finalThreshold = threshold == null ? Double.MAX_VALUE : threshold;
            selected = eligible.stream()
                    .filter(m -> m.weightedScore() + EPSILON >= finalThreshold)
                    .sorted(Comparator.comparingDouble(CompanyMetrics::weightedScore).reversed()
                            .thenComparing(Comparator.comparingInt(CompanyMetrics::recommendSample).reversed())
                            .thenComparing(CompanyMetrics::uscc))
                    .toList();
        } else {
            selected = metrics.stream()
                    .filter(m -> m.improveEligible(rankingProperties.getMinSample(),
                            IMPROVE_LOW_THRESHOLD, IMPROVE_DISPUTE_THRESHOLD))
                    .sorted(Comparator.comparingDouble(CompanyMetrics::improveScore).reversed()
                            .thenComparing(Comparator.comparingInt(CompanyMetrics::improveSample).reversed())
                            .thenComparing(CompanyMetrics::uscc))
                    .toList();
        }

        List<RankingItemView> items = new ArrayList<>();
        int rank = 1;
        for (CompanyMetrics metric : selected) {
            if (items.size() >= limit) {
                break;
            }
            if (containsForbiddenWord(metric.name())) {
                continue;
            }
            int sampleCount = type == RankingType.RECOMMEND
                    ? metric.recommendSample() : metric.improveSample();
            items.add(new RankingItemView(
                    rank++,
                    metric.uscc(),
                    metric.name(),
                    sampleCount,
                    metric.weightedScore(),
                    metric.lowScoreConcentration(),
                    metric.disputeRate(),
                    metric.improveScore(),
                    metric.lowScoreConcentration(),
                    metric.disputeRate()));
        }
        return new RankingPageView(city, industry, type, rankingProperties.getAlgorithmVersion(),
                Instant.now(), threshold, params(type), METRIC_DEFINITIONS,
                1, items.isEmpty() ? limit : items.size(), items.size(), items);
    }

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    /**
     * 重算所有非空城市+行业的两类榜单并写入快照；返回写入条数。
     */
    public int recomputeAllSnapshots() {
        List<Map<String, Object>> groups = jdbcTemplate.queryForList("""
                SELECT DISTINCT city, industry
                FROM company
                WHERE status = 'ACTIVE'
                  AND city IS NOT NULL AND btrim(city) <> ''
                  AND industry IS NOT NULL AND btrim(industry) <> ''
                ORDER BY city, industry
                """);
        int written = 0;
        for (Map<String, Object> group : groups) {
            String city = String.valueOf(group.get("city"));
            String industry = String.valueOf(group.get("industry"));
            for (RankingType type : RankingType.values()) {
                RankingPageView page = computePage(city, industry, type, Integer.MAX_VALUE);
                saveSnapshot(page);
                written++;
            }
        }
        evictCache();
        return written;
    }

    /**
     * 快照重算后清理榜单查询缓存，避免继续返回旧数据；Redis 故障不影响任务本身。
     */
    public void evictCache() {
        try {
            redis.deleteByPattern(CACHE_PREFIX + "*");
        } catch (Exception ex) {
            log.warn("榜单缓存清理失败，忽略：{}", ex.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 缓存（Redis，JSON 文本）
    // ------------------------------------------------------------------

    private String cacheKey(String city, String industry, RankingType type, int limit) {
        return redis.key(CACHE_PREFIX + city + ":" + industry + ":" + type.name() + ":" + limit);
    }

    private Optional<RankingPageView> readCache(String cacheKey) {
        try {
            Optional<String> raw = redis.find(cacheKey);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw.get(), RankingPageView.class));
        } catch (Exception ex) {
            log.warn("榜单缓存读取失败，降级查库：{}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void writeCache(String cacheKey, RankingPageView page) {
        try {
            redis.put(cacheKey, objectMapper.writeValueAsString(page), CACHE_TTL);
        } catch (Exception ex) {
            log.warn("榜单缓存写入失败，忽略：{}", ex.getClass().getSimpleName());
        }
    }

    private void saveSnapshot(RankingPageView page) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            if (page.threshold() != null) {
                payload.put("threshold", page.threshold());
            }
            payload.set("metricDefinitions", objectMapper.valueToTree(page.metricDefinitions()));
            payload.set("items", objectMapper.valueToTree(page.items()));
            String paramsJson = objectMapper.writeValueAsString(page.params());
            String payloadJson = objectMapper.writeValueAsString(payload);
            jdbcTemplate.update("""
                            INSERT INTO ranking_snapshot
                                (city, industry, ranking_type, algorithm_version,
                                 params_json, payload_json, computed_at)
                            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, now())
                            """,
                    page.city(), page.industry(), page.type().name(),
                    page.algorithmVersion(), paramsJson, payloadJson);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "榜单快照写入失败");
        }
    }

    // ------------------------------------------------------------------
    // 参数与工具
    // ------------------------------------------------------------------

    private Map<String, Object> params(RankingType type) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("algorithmVersion", rankingProperties.getAlgorithmVersion());
        params.put("rankingType", type.name());
        params.put("minSample", rankingProperties.getMinSample());
        params.put("recommendPercentile", rankingProperties.getRecommendPercentile());
        params.put("timeDecay", Map.of(
                "enabled", rankingProperties.getTimeDecay().isEnabled(),
                "months", rankingProperties.getTimeDecay().getMonths(),
                "weight", rankingProperties.getTimeDecay().getWeight()));
        params.put("weights", Map.of(
                "verified", rankingProperties.getVerifiedWeight(),
                "former", rankingProperties.getFormerWeight(),
                "evidenceBonus", rankingProperties.getEvidenceBonus(),
                "copyPenalty", rankingProperties.getCopyPenalty()));
        params.put("improve", Map.of(
                "lowScoreConcentrationThreshold", IMPROVE_LOW_THRESHOLD,
                "disputeRateThreshold", IMPROVE_DISPUTE_THRESHOLD,
                "lowScoreWeight", IMPROVE_LOW_WEIGHT,
                "disputeWeight", IMPROVE_DISPUTE_WEIGHT));
        params.put("metricDefinitions", METRIC_DEFINITIONS);
        return params;
    }

    private Double percentileThreshold(List<CompanyMetrics> eligible) {
        if (eligible.isEmpty()) {
            return null;
        }
        List<Double> scores = eligible.stream()
                .map(CompanyMetrics::weightedScore)
                .sorted()
                .toList();
        double percentile = Math.max(0.01, Math.min(1.0, rankingProperties.getRecommendPercentile()));
        int index = (int) Math.ceil((1.0 - percentile) * scores.size()) - 1;
        index = Math.max(0, Math.min(scores.size() - 1, index));
        return scores.get(index);
    }

    private static boolean containsForbiddenWord(String value) {
        if (value == null) {
            return false;
        }
        for (String word : FORBIDDEN_WORDS) {
            if (value.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static int safeLimit(int limit) {
        return Math.min(Math.max(1, limit), 200);
    }

    private static RankingType parseType(String raw) {
        try {
            return RankingType.from(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "type 不正确");
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "date 不能为空");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "date 格式应为 YYYY-MM-DD");
        }
    }

    private static void requireGroup(String city, String industry) {
        if (city == null || city.isBlank() || industry == null || industry.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "city 与 industry 不能为空");
        }
    }

    private record SnapshotRow(String algorithmVersion, String paramsJson,
                               String payloadJson, Instant computedAt) {
    }
}
