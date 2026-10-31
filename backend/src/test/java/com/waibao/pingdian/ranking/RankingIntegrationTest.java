package com.waibao.pingdian.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.ranking.domain.CompanyMetrics;
import com.waibao.pingdian.ranking.service.RankingMetricsService;
import com.waibao.pingdian.ranking.service.RankingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Task 10：推荐榜分位阈值、待改善名单口径与排序、快照与历史回溯（R13）。
 */
class RankingIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String[] BLACKLIST = {"黑店", "骗子", "黑心"};

    @Autowired
    private RankingMetricsService metricsService;

    @Autowired
    private RankingService rankingService;

    private record Actor(String token, UUID userId) {
    }

    private String newUscc() {
        return "92" + String.format("%016d", 90000 + SEQ.incrementAndGet());
    }

    private Actor author() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        return new Actor(login.path("token").asText(), userId);
    }

    private UUID identityOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ?", UUID.class, userId);
    }

    private String createCompany(String city, String industry, String shortName) {
        String uscc = newUscc();
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', 'ACTIVE')",
                uscc, shortName + uscc.substring(14), city, industry);
        return uscc;
    }

    private UUID insertReview(String uscc, Actor author, String badge, int score,
                              String status, boolean evidence, boolean copy,
                              int ageMonths, boolean everDisputed) {
        UUID reviewId = UUID.randomUUID();
        String flags = copy ? "{\"copyDetected\":true}" : null;
        Instant publishedAt = Instant.now().atZone(BUSINESS_ZONE)
                .minusMonths(ageMonths).toInstant();
        jdbcTemplate.update("""
                        INSERT INTO review
                            (id, uscc, user_id, identity_id, verification_badge, total_score,
                             content, status, ai_flags, published_at, ever_disputed)
                        VALUES (?, ?, ?, ?, ?, ?, '榜单计算测试评价内容，不包含任何定性词。',
                                ?, ?::jsonb, ?, ?)
                        """,
                reviewId, uscc, author.userId(), identityOf(author.userId()), badge,
                score, status, flags, Timestamp.from(publishedAt), everDisputed);
        if (evidence) {
            jdbcTemplate.update("INSERT INTO review_evidence (review_id, file_key, type, material_status) "
                    + "VALUES (?, ?, 'OTHER', 'ACTIVE')", reviewId, "ranking-" + reviewId);
        }
        return reviewId;
    }

    private void addReviews(String uscc, Actor author, String badge, String status,
                            int count, int score, boolean evidence, boolean copy,
                            int ageMonths, boolean everDisputed) {
        for (int i = 0; i < count; i++) {
            insertReview(uscc, author, badge, score, status, evidence, copy, ageMonths, everDisputed);
        }
    }

    private JsonNode rankings(String city, String industry, String type) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/rankings")
                        .param("city", city)
                        .param("industry", industry)
                        .param("type", type)
                        .param("limit", "50"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        for (String word : BLACKLIST) {
            assertThat(body.toString()).doesNotContain(word);
        }
        return body.path("data");
    }

    private JsonNode findItem(JsonNode page, String uscc) {
        for (JsonNode item : page.path("items")) {
            if (uscc.equals(item.path("uscc").asText())) {
                return item;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 推荐榜：分位阈值、样本门槛、争议/折叠排除、排序
    // ------------------------------------------------------------------

    @Test
    void recommendRankUsesPercentileThresholdAndSortsByScoreThenSample() throws Exception {
        Actor author = author();
        String city = "推荐榜城" + SEQ.incrementAndGet();
        String industry = "推荐行业" + SEQ.incrementAndGet();
        String a = createCompany(city, industry, "推荐A公司");
        String b = createCompany(city, industry, "推荐B公司");
        String c = createCompany(city, industry, "推荐C公司");
        String d = createCompany(city, industry, "推荐D公司");
        String e = createCompany(city, industry, "推荐E公司");
        String f = createCompany(city, industry, "推荐F公司");
        String g = createCompany(city, industry, "推荐G公司");
        String p = createCompany(city, industry, "推荐P公司");

        addReviews(a, author, "CURRENT", "PUBLISHED", 4, 5, true, false, 1, false);
        addReviews(b, author, "CURRENT", "PUBLISHED", 3, 5, true, false, 1, false);
        // C：2 条带佐证 + 1 条无佐证 → (2.2 + 2.2 + 2.0) / 3 ≈ 2.1333，低于 2.2 阈值。
        addReviews(c, author, "CURRENT", "PUBLISHED", 2, 5, true, false, 1, false);
        addReviews(c, author, "CURRENT", "PUBLISHED", 1, 5, false, false, 1, false);
        // D：曾在职 + 超 12 个月 + 无佐证 → 0.8 + 0.5 = 1.3。
        addReviews(d, author, "FORMER", "PUBLISHED", 3, 5, false, false, 13, false);
        // E：样本 2，不入选。
        addReviews(e, author, "CURRENT", "PUBLISHED", 2, 5, true, false, 1, false);
        // F：3 条 PUBLISHED + 1 条 DISPUTED，争议中不计入推荐样本。
        addReviews(f, author, "CURRENT", "PUBLISHED", 3, 5, false, false, 1, false);
        insertReview(f, author, "CURRENT", 5, "DISPUTED", true, false, 1, true);
        // G：一条复制检测命中，权重 ×0.5 → (1.0 + 2.0 + 2.0) / 3 ≈ 1.6667。
        addReviews(g, author, "CURRENT", "PUBLISHED", 2, 5, false, false, 1, false);
        addReviews(g, author, "CURRENT", "PUBLISHED", 1, 5, false, true, 1, false);
        // P：2 条 PUBLISHED + 1 条 FOLDED，折叠不计入样本 → 样本 2，不入选。
        addReviews(p, author, "CURRENT", "PUBLISHED", 2, 5, true, false, 1, false);
        insertReview(p, author, "CURRENT", 5, "FOLDED", true, false, 1, false);

        CompanyMetrics metricsA = metricsService.compute(a, "推荐A公司");
        assertThat(metricsA.recommendSample()).isEqualTo(4);
        assertThat(metricsA.weightedScore()).isCloseTo(2.2, within(0.0001));
        assertThat(metricsService.compute(c, "推荐C公司").weightedScore())
                .isCloseTo(2.1333, within(0.0001));
        assertThat(metricsService.compute(d, "推荐D公司").weightedScore())
                .isCloseTo(1.3, within(0.0001));
        assertThat(metricsService.compute(e, "推荐E公司").recommendSample()).isEqualTo(2);
        assertThat(metricsService.compute(f, "推荐F公司").weightedScore())
                .isCloseTo(2.0, within(0.0001));
        assertThat(metricsService.compute(g, "推荐G公司").weightedScore())
                .isCloseTo(1.6667, within(0.0001));

        JsonNode page = rankings(city, industry, "RECOMMEND");
        assertThat(page.path("algorithmVersion").asText()).isEqualTo("ranking-v1");
        assertThat(page.path("threshold").asDouble()).isCloseTo(2.2, within(0.0001));
        assertThat(page.path("items").size()).isEqualTo(2);
        JsonNode first = page.path("items").get(0);
        JsonNode second = page.path("items").get(1);
        assertThat(first.path("uscc").asText()).isEqualTo(a);
        assertThat(first.path("sampleCount").asInt()).isEqualTo(4);
        assertThat(first.path("weightedScore").asDouble()).isCloseTo(2.2, within(0.0001));
        assertThat(second.path("uscc").asText()).isEqualTo(b);
        assertThat(second.path("sampleCount").asInt()).isEqualTo(3);
        assertThat(findItem(page, c)).isNull();
        assertThat(findItem(page, d)).isNull();
        assertThat(findItem(page, e)).isNull();
        assertThat(findItem(page, f)).isNull();
        assertThat(findItem(page, g)).isNull();
        assertThat(findItem(page, p)).isNull();
    }

    // ------------------------------------------------------------------
    // 待改善名单：40% / 39% 边界、争议率 30%、排序分与同分样本数
    // ------------------------------------------------------------------

    @Test
    void improveRankUsesLowScoreConcentrationAndDisputeRate() throws Exception {
        Actor author = author();
        String city = "待改善城" + SEQ.incrementAndGet();
        String industry = "待改善行业" + SEQ.incrementAndGet();

        String h = createCompany(city, industry, "待改善H公司");
        addReviews(h, author, "CURRENT", "PUBLISHED", 2, 1, false, false, 1, false);
        addReviews(h, author, "CURRENT", "PUBLISHED", 3, 5, false, false, 1, false);

        // 39% 低分 + 20% 争议率，两个条件均未达阈值。
        String i = createCompany(city, industry, "待改善I公司");
        addReviews(i, author, "CURRENT", "PUBLISHED", 39, 1, false, false, 1, false);
        addReviews(i, author, "CURRENT", "PUBLISHED", 20, 5, false, false, 1, true);
        addReviews(i, author, "CURRENT", "PUBLISHED", 41, 5, false, false, 1, false);

        // 争议率 30% 入榜。
        String j = createCompany(city, industry, "待改善J公司");
        addReviews(j, author, "CURRENT", "PUBLISHED", 3, 5, false, false, 1, true);
        addReviews(j, author, "CURRENT", "PUBLISHED", 7, 5, false, false, 1, false);

        // K / L 同排序分 0.32，L 样本更多，应排在 K 之前。
        String k = createCompany(city, industry, "待改善K公司");
        addReviews(k, author, "CURRENT", "PUBLISHED", 2, 1, false, false, 1, false);
        addReviews(k, author, "CURRENT", "PUBLISHED", 1, 5, false, false, 1, true);
        addReviews(k, author, "CURRENT", "PUBLISHED", 2, 5, false, false, 1, false);
        String l = createCompany(city, industry, "待改善L公司");
        addReviews(l, author, "CURRENT", "PUBLISHED", 4, 1, false, false, 1, false);
        addReviews(l, author, "CURRENT", "PUBLISHED", 2, 5, false, false, 1, true);
        addReviews(l, author, "CURRENT", "PUBLISHED", 4, 5, false, false, 1, false);

        // 样本 <3 不入选；33% 低分且争议率 0 不入选。
        String n = createCompany(city, industry, "待改善N公司");
        addReviews(n, author, "CURRENT", "PUBLISHED", 2, 1, false, false, 1, false);
        String o = createCompany(city, industry, "待改善O公司");
        addReviews(o, author, "CURRENT", "PUBLISHED", 1, 1, false, false, 1, false);
        addReviews(o, author, "CURRENT", "PUBLISHED", 2, 5, false, false, 1, false);

        CompanyMetrics metricsH = metricsService.compute(h, "待改善H公司");
        assertThat(metricsH.improveSample()).isEqualTo(5);
        assertThat(metricsH.lowScoreConcentration()).isCloseTo(0.4, within(0.0001));
        assertThat(metricsH.disputeRate()).isCloseTo(0.0, within(0.0001));
        CompanyMetrics metricsI = metricsService.compute(i, "待改善I公司");
        assertThat(metricsI.lowScoreConcentration()).isCloseTo(0.39, within(0.0001));
        assertThat(metricsI.disputeRate()).isCloseTo(0.20, within(0.0001));
        CompanyMetrics metricsL = metricsService.compute(l, "待改善L公司");
        assertThat(metricsL.improveScore()).isCloseTo(0.32, within(0.0001));

        JsonNode page = rankings(city, industry, "IMPROVE");
        JsonNode items = page.path("items");
        assertThat(items.size()).isEqualTo(4);
        String[] expected = {l, k, h, j};
        for (int idx = 0; idx < expected.length; idx++) {
            assertThat(items.get(idx).path("uscc").asText()).isEqualTo(expected[idx]);
        }
        assertThat(items.get(0).path("sampleCount").asInt()).isEqualTo(10);
        assertThat(items.get(1).path("sampleCount").asInt()).isEqualTo(5);
        assertThat(items.get(0).path("score").asDouble()).isCloseTo(0.32, within(0.0001));
        assertThat(items.get(2).path("score").asDouble()).isCloseTo(0.24, within(0.0001));
        assertThat(items.get(3).path("score").asDouble()).isCloseTo(0.12, within(0.0001));
        assertThat(findItem(page, i)).isNull();
        assertThat(findItem(page, n)).isNull();
        assertThat(findItem(page, o)).isNull();

        JsonNode definitions = page.path("metricDefinitions");
        assertThat(definitions.path("lowScoreConcentration").asText()).contains("PUBLISHED", "DISPUTED");
        assertThat(definitions.path("disputeRate").asText()).contains("ever_disputed");
        assertThat(page.path("params").path("improve").path("lowScoreConcentrationThreshold").asDouble())
                .isCloseTo(0.4, within(0.0001));
    }

    // ------------------------------------------------------------------
    // 快照写入与历史回溯
    // ------------------------------------------------------------------

    @Test
    void snapshotsStoreAlgorithmParamsAndHistoryIsQueryable() throws Exception {
        Actor author = author();
        String city = "快照城" + SEQ.incrementAndGet();
        String industry = "快照行业" + SEQ.incrementAndGet();
        String a = createCompany(city, industry, "快照A公司");
        String b = createCompany(city, industry, "快照B公司");
        addReviews(a, author, "CURRENT", "PUBLISHED", 3, 5, true, false, 1, false);
        addReviews(b, author, "CURRENT", "PUBLISHED", 3, 3, false, false, 1, false);

        int written = rankingService.recomputeAllSnapshots();
        assertThat(written).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ranking_snapshot WHERE city = ? AND industry = ?",
                Integer.class, city, industry)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT algorithm_version FROM ranking_snapshot WHERE city = ? AND industry = ? "
                        + "AND ranking_type = 'RECOMMEND'", String.class, city, industry))
                .isEqualTo("ranking-v1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT params_json::text FROM ranking_snapshot WHERE city = ? AND industry = ? "
                        + "AND ranking_type = 'RECOMMEND'", String.class, city, industry))
                .contains("minSample", "recommendPercentile", "timeDecay");

        String today = LocalDate.now(BUSINESS_ZONE).toString();
        MvcResult history = mockMvc.perform(get("/api/rankings/history")
                        .param("city", city)
                        .param("industry", industry)
                        .param("type", "RECOMMEND")
                        .param("date", today))
                .andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        JsonNode page = body(history).path("data");
        assertThat(page.path("algorithmVersion").asText()).isEqualTo("ranking-v1");
        assertThat(page.path("params").path("minSample").asInt()).isEqualTo(3);
        assertThat(page.path("computedAt").asText()).isNotBlank();
        assertThat(page.path("items").size()).isGreaterThanOrEqualTo(1);
        for (String word : BLACKLIST) {
            assertThat(body(history).toString()).doesNotContain(word);
        }

        MvcResult missing = mockMvc.perform(get("/api/rankings/history")
                        .param("city", city)
                        .param("industry", industry)
                        .param("type", "RECOMMEND")
                        .param("date", LocalDate.now(BUSINESS_ZONE).minusDays(3).toString()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // 缓存：命中 300s 缓存，快照重算后按前缀清理
    // ------------------------------------------------------------------

    @Test
    void rankingQueryIsCachedUntilSnapshotEviction() throws Exception {
        Actor author = author();
        String city = "榜单缓存城" + SEQ.incrementAndGet();
        String industry = "榜单缓存行业" + SEQ.incrementAndGet();
        String a = createCompany(city, industry, "缓存A公司");
        addReviews(a, author, "CURRENT", "PUBLISHED", 3, 5, true, false, 1, false);

        JsonNode first = rankings(city, industry, "RECOMMEND");
        assertThat(first.path("items").size()).isEqualTo(1);
        String cacheKey = redisSupport.key("ranking:" + city + ":" + industry + ":RECOMMEND:50");
        assertThat(redisSupport.find(cacheKey)).isPresent();
        assertThat(redisSupport.remainingSeconds(cacheKey)).isBetween(290L, 300L);

        String b = createCompany(city, industry, "缓存B公司");
        addReviews(b, author, "CURRENT", "PUBLISHED", 3, 5, true, false, 1, false);
        JsonNode cached = rankings(city, industry, "RECOMMEND");
        assertThat(cached.path("items").size()).isEqualTo(1);

        rankingService.recomputeAllSnapshots();
        assertThat(redisSupport.find(cacheKey)).isEmpty();
        JsonNode recomputed = rankings(city, industry, "RECOMMEND");
        assertThat(recomputed.path("items").size()).isEqualTo(2);
    }
}
