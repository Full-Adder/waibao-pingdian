package com.waibao.pingdian.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
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
 * Task 12：14 项指标与手工 SQL 一致、北极星、漏斗、三条红线边界、30 天复访与越权。
 */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CONTENT = "看板测试评价内容，描述客观经历，不包含定性词或联系方式。";

    private record Actor(String token, UUID userId) {
    }

    private String newUscc() {
        return "96" + String.format("%016d", 100000 + SEQ.incrementAndGet());
    }

    private Actor register() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        return new Actor(login.path("token").asText(),
                UUID.fromString(login.path("user").path("id").asText()));
    }

    private UUID identityOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ?", UUID.class, userId);
    }

    private String createCompany(String city, String industry, String name) {
        String uscc = newUscc();
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', 'ACTIVE')",
                uscc, name + uscc.substring(12), city, industry);
        return uscc;
    }

    private UUID insertReview(String uscc, Actor author, String badge, String status,
                              Instant publishedAt) {
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review
                            (id, uscc, user_id, identity_id, verification_badge, total_score,
                             content, status, published_at)
                        VALUES (?, ?, ?, ?, ?, 4, ?, ?, ?)
                        """,
                reviewId, uscc, author.userId(), identityOf(author.userId()), badge,
                CONTENT, status, publishedAt == null ? null : Timestamp.from(publishedAt));
        return reviewId;
    }

    private void insertAudit(UUID reviewId, String action, String actorType,
                             String fromStatus, String toStatus, Instant createdAt,
                             String reasonCode) {
        jdbcTemplate.update("""
                        INSERT INTO review_audit
                            (review_id, stage, actor_type, action, from_status, to_status,
                             reason_code, created_at)
                        VALUES (?, 'TEST', ?, ?, ?, ?, ?, ?)
                        """,
                reviewId, actorType, action, fromStatus, toStatus, reasonCode,
                Timestamp.from(createdAt));
    }

    private void insertVerification(UUID userId, String uscc, Instant reviewedAt) {
        insertVerification(userId, uscc, "APPROVED", reviewedAt);
    }

    private void insertVerification(UUID userId, String uscc, String status, Instant reviewedAt) {
        jdbcTemplate.update("""
                        INSERT INTO user_verification
                            (user_id, proof_type, company_uscc, period_start, period_end,
                             status, reviewed_at)
                        VALUES (?, 'LABOR_CONTRACT', ?, ?, ?, ?, ?)
                        """,
                userId, uscc,
                LocalDate.now(BUSINESS_ZONE).minusMonths(6).withDayOfMonth(1),
                LocalDate.now(BUSINESS_ZONE).plusMonths(1).withDayOfMonth(1),
                status,
                reviewedAt == null ? null : Timestamp.from(reviewedAt));
    }

    private void insertComplaint(UUID reviewId, String uscc, String status) {
        jdbcTemplate.update("""
                        INSERT INTO complaint
                            (review_id, claimant_uscc, claimant_contact_cipher, status)
                        VALUES (?, ?, 'test-cipher', ?)
                        """,
                reviewId, uscc, status);
    }

    private void insertReport(UUID reviewId, UUID reporterId) {
        jdbcTemplate.update("""
                        INSERT INTO report (target_type, target_id, user_id, reason_code, status)
                        VALUES ('REVIEW', ?, ?, 'FALSE_INFO', 'PENDING')
                        """,
                reviewId, reporterId);
    }

    private void insertSearch(String city, String industry, int resultCount) {
        jdbcTemplate.update("""
                        INSERT INTO search_log (keyword, result_count, city, industry, visitor_id)
                        VALUES ('看板测试', ?, ?, ?, NULL)
                        """,
                resultCount, city, industry);
    }

    private void insertVisit(String visitorId, String path, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO visit_event (visitor_id, path, created_at) VALUES (?, ?, ?)",
                visitorId, path, Timestamp.from(createdAt));
    }

    private JsonNode dashboard(String adminToken, String city, String industry,
                               LocalDate from, LocalDate to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/dashboard")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("city", city)
                        .param("industry", industry)
                        .param("refresh", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result).path("data");
    }

    private JsonNode dashboardCached(String adminToken, String city, String industry,
                                     LocalDate from, LocalDate to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/dashboard")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("city", city)
                        .param("industry", industry)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result).path("data");
    }

    private JsonNode metric(JsonNode data, String group, String key) {
        for (JsonNode node : data.path(group)) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing metric " + group + "." + key);
    }

    private JsonNode redLine(JsonNode data, String key) {
        for (JsonNode node : data.path("healthRedLines")) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing red line " + key);
    }

    private long manualCount(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long[] manualCounts(String sql, Object... args) {
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return new long[]{0, 0};
            }
            return new long[]{rs.getLong(1), rs.getLong(2)};
        }, args);
    }

    // ------------------------------------------------------------------
    // 14 项指标与手工 SQL 一致 + 漏斗
    // ------------------------------------------------------------------

    @Test
    void allMetricsAndFunnelMatchManualSql() throws Exception {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = today.minusDays(6);
        Instant start = from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Timestamp startTs = Timestamp.from(start);
        Timestamp endTs = Timestamp.from(end);

        Actor author = register();
        Actor reporter = register();
        String city = "看板城" + SEQ.incrementAndGet();
        String industry = "看板行业" + SEQ.incrementAndGet();
        String uscc = createCompany(city, industry, "看板一公司");
        createCompany(city, industry, "看板二公司");

        jdbcTemplate.update("UPDATE user_account SET realname_status = 'VERIFIED', "
                + "realname_verified_at = now() WHERE id = ?", author.userId());
        insertVerification(author.userId(), uscc, Instant.now());
        // 第二个用户仅有一条待审核验，用于把其纳入该公司维度的用户口径（不改变已核验人数）。
        insertVerification(reporter.userId(), uscc, "PENDING", null);

        Instant now = Instant.now();
        UUID r1 = insertReview(uscc, author, "CURRENT", "PUBLISHED", now);
        UUID r2 = insertReview(uscc, author, "FORMER", "PUBLISHED", now);
        UUID r3 = insertReview(uscc, author, "FORMER", "PUBLISHED", now);

        for (UUID reviewId : new UUID[]{r1, r2, r3}) {
            insertAudit(reviewId, "SUBMIT", "USER", "DRAFT", "MACHINE_REVIEWING",
                    now.minusSeconds(20), null);
            insertAudit(reviewId, "AI_PUBLISH", "SYSTEM", "MACHINE_REVIEWING", "PUBLISHED",
                    now.minusSeconds(10), null);
        }
        insertAudit(r3, "APPROVE", "ADMIN", "PENDING_MANUAL", "PUBLISHED",
                now.plusSeconds(1), "MANUAL_CONFIRMED");
        insertAudit(r3, "APPROVE", "ADMIN", "PENDING_MANUAL", "PUBLISHED",
                now.plusSeconds(2), "MANUAL_CONFIRMED");
        insertAudit(r3, "FOLD", "ADMIN", "PENDING_MANUAL", "FOLDED",
                now.plusSeconds(3), "INSUFFICIENT_EVIDENCE");
        insertAudit(r3, "REJECT", "ADMIN", "PENDING_MANUAL", "REJECTED",
                now.plusSeconds(4), "FALSE_CLAIM");

        insertComplaint(r1, uscc, "RULED_USER_WIN");
        insertReport(r1, reporter.userId());

        for (int i = 0; i < 4; i++) {
            insertSearch(city, industry, 3);
        }
        insertSearch(city, industry, 0);

        String visitor = "visitor-" + UUID.randomUUID();
        insertVisit(visitor, "/company", now);
        insertVisit(visitor, "/rankings", now.minusSeconds(86400));
        insertVisit("visitor-" + UUID.randomUUID(), "/", now);
        insertVisit("visitor-old-" + UUID.randomUUID(), "/", now.minusSeconds(40 * 86400));

        String admin = adminToken("CONTENT_ADMIN");
        JsonNode data = dashboard(admin, city, industry, from, today);

        assertThat(data.path("window").path("from").asText()).isEqualTo(from.toString());
        assertThat(data.path("window").path("to").asText()).isEqualTo(today.toString());

        // 北极星：3 条 PUBLISHED 且 1 条 CURRENT。
        long[] northstar = manualCounts("""
                        SELECT count(*) FILTER (WHERE cnt >= 3 AND cur >= 1),
                               count(*) FILTER (WHERE cnt >= 3)
                        FROM (
                            SELECT r.uscc, count(*) AS cnt,
                                   count(*) FILTER (WHERE r.verification_badge = 'CURRENT') AS cur
                            FROM review r JOIN company c ON c.uscc = r.uscc
                            WHERE r.status = 'PUBLISHED'
                              AND r.published_at >= ? AND r.published_at < ?
                              AND c.city = ? AND c.industry = ?
                            GROUP BY r.uscc
                        ) t
                        """, startTs, endTs, city, industry);
        assertThat(data.path("northstar").path("value").asDouble()).isEqualTo(northstar[0]);
        assertThat(data.path("northstar").path("numerator").asLong()).isEqualTo(northstar[0]);
        assertThat(data.path("northstar").path("denominator").asLong()).isEqualTo(northstar[1]);

        // 供给
        long registered = 2L;
        long approvedUsers = manualCount("""
                        SELECT count(DISTINCT uv.user_id) FROM user_verification uv
                        JOIN company c ON c.uscc = uv.company_uscc
                        WHERE uv.status = 'APPROVED'
                          AND uv.reviewed_at >= ? AND uv.reviewed_at < ?
                          AND c.city = ? AND c.industry = ?
                        """, startTs, endTs, city, industry);
        long published = manualCount("""
                        SELECT count(*) FROM review r JOIN company c ON c.uscc = r.uscc
                        WHERE r.status = 'PUBLISHED'
                          AND r.published_at >= ? AND r.published_at < ?
                          AND c.city = ? AND c.industry = ?
                        """, startTs, endTs, city, industry);
        long publishingUsers = manualCount("""
                        SELECT count(DISTINCT r.user_id) FROM review r
                        JOIN company c ON c.uscc = r.uscc
                        WHERE r.status = 'PUBLISHED'
                          AND r.published_at >= ? AND r.published_at < ?
                          AND c.city = ? AND c.industry = ?
                        """, startTs, endTs, city, industry);

        assertThat(metric(data, "supply", "registrationVerificationRate").path("value").asDouble())
                .isEqualTo(round(approvedUsers * 100.0 / registered));
        assertThat(metric(data, "supply", "registrationVerificationRate").path("numerator").asLong())
                .isEqualTo(approvedUsers);
        assertThat(metric(data, "supply", "registrationVerificationRate").path("denominator").asLong())
                .isEqualTo(registered);
        assertThat(metric(data, "supply", "verificationFirstReviewRate").path("value").asDouble())
                .isEqualTo(100.0);
        assertThat(metric(data, "supply", "avgPublishedReviewsPerUser").path("value").asDouble())
                .isEqualTo(round(published * 1.0 / publishingUsers));
        assertThat(metric(data, "supply", "avgPublishedReviewsPerUser").path("numerator").asLong())
                .isEqualTo(published);

        // 覆盖
        long activeCompanies = manualCount("""
                        SELECT count(*) FROM company c
                        WHERE c.status = 'ACTIVE' AND c.city = ? AND c.industry = ?
                        """, city, industry);
        long withReviews = manualCount("""
                        SELECT count(*) FROM (
                            SELECT r.uscc FROM review r JOIN company c ON c.uscc = r.uscc
                            WHERE r.status = 'PUBLISHED' AND c.city = ? AND c.industry = ?
                            GROUP BY r.uscc
                        ) t
                        """, city, industry);
        long qualified = manualCount("""
                        SELECT count(*) FROM (
                            SELECT r.uscc FROM review r JOIN company c ON c.uscc = r.uscc
                            WHERE r.status = 'PUBLISHED' AND c.city = ? AND c.industry = ?
                            GROUP BY r.uscc HAVING count(*) >= 3
                        ) t
                        """, city, industry);
        assertThat(metric(data, "coverage", "companyCount").path("value").asDouble())
                .isEqualTo(activeCompanies);
        assertThat(metric(data, "coverage", "companiesWithReviews").path("value").asDouble())
                .isEqualTo(withReviews);
        assertThat(metric(data, "coverage", "qualifiedCompanies").path("value").asDouble())
                .isEqualTo(qualified);

        // 质量
        long[] ai = manualCounts("""
                        WITH submits AS (
                            SELECT a.review_id, min(a.created_at) AS submitted_at
                            FROM review_audit a
                            JOIN review r ON r.id = a.review_id
                            JOIN company c ON c.uscc = r.uscc
                            WHERE a.action = 'SUBMIT'
                              AND a.created_at >= ? AND a.created_at < ?
                              AND c.city = ? AND c.industry = ?
                            GROUP BY a.review_id
                        )
                        SELECT count(*) FILTER (WHERE EXISTS (
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
                               )), count(*)
                        FROM submits s
                        """, startTs, endTs, city, industry);
        long[] manual = manualCounts("""
                        SELECT count(*) FILTER (WHERE a.action = 'REJECT'), count(*)
                        FROM review_audit a
                        JOIN review r ON r.id = a.review_id
                        JOIN company c ON c.uscc = r.uscc
                        WHERE a.actor_type = 'ADMIN'
                          AND a.action IN ('APPROVE', 'FOLD', 'REJECT')
                          AND a.created_at >= ? AND a.created_at < ?
                          AND c.city = ? AND c.industry = ?
                        """, startTs, endTs, city, industry);
        long[] complaint = manualCounts("""
                        SELECT count(*) FILTER (WHERE co.status = 'RULED_USER_WIN'), count(*)
                        FROM complaint co
                        JOIN review r ON r.id = co.review_id
                        JOIN company c ON c.uscc = r.uscc
                        WHERE co.status IN ('RULED_USER_WIN', 'RULED_COMPANY_WIN', 'RULED_INSUFFICIENT')
                          AND co.created_at >= ? AND co.created_at < ?
                          AND c.city = ? AND c.industry = ?
                        """, startTs, endTs, city, industry);

        assertThat(metric(data, "quality", "aiAutoPublishRate").path("value").asDouble())
                .isEqualTo(round(ai[0] * 100.0 / ai[1]));
        assertThat(metric(data, "quality", "aiAutoPublishRate").path("numerator").asLong())
                .isEqualTo(ai[0]);
        assertThat(metric(data, "quality", "manualRejectRate").path("value").asDouble())
                .isEqualTo(round(manual[0] * 100.0 / manual[1]));
        assertThat(metric(data, "quality", "complaintOverturnRate").path("value").asDouble())
                .isEqualTo(round(complaint[0] * 100.0 / complaint[1]));

        // 需求
        long uv = manualCount("""
                        SELECT count(DISTINCT visitor_id) FROM visit_event
                        WHERE visitor_id IS NOT NULL AND created_at >= ? AND created_at < ?
                        """, startTs, endTs);
        long[] searchZero = manualCounts("""
                        SELECT count(*) FILTER (WHERE result_count = 0), count(*)
                        FROM search_log
                        WHERE created_at >= ? AND created_at < ? AND city = ? AND industry = ?
                        """, startTs, endTs, city, industry);
        assertThat(metric(data, "demand", "weeklyUv").path("value").asDouble()).isEqualTo(uv);
        assertThat(metric(data, "demand", "searchZeroResultRate").path("value").asDouble())
                .isEqualTo(round(searchZero[0] * 100.0 / searchZero[1]));

        // 生态
        long reports = manualCount("""
                        SELECT count(*) FROM report rp
                        WHERE rp.created_at >= ? AND rp.created_at < ?
                          AND rp.target_type = 'REVIEW'
                          AND EXISTS (
                              SELECT 1 FROM review r JOIN company c ON c.uscc = r.uscc
                              WHERE r.id = rp.target_id AND c.city = ? AND c.industry = ?
                          )
                        """, startTs, endTs, city, industry);
        Timestamp revisitStart = Timestamp.from(end.minusSeconds(30 * 86400));
        long[] revisit = manualCounts("""
                        SELECT count(*) FILTER (WHERE day_count >= 2), count(*)
                        FROM (
                            SELECT visitor_id,
                                   count(DISTINCT (created_at AT TIME ZONE 'Asia/Shanghai')::date)
                                       AS day_count
                            FROM visit_event
                            WHERE visitor_id IS NOT NULL AND created_at >= ? AND created_at < ?
                            GROUP BY visitor_id
                        ) t
                        """, revisitStart, endTs);
        assertThat(metric(data, "ecology", "reportDensity").path("value").asDouble())
                .isEqualTo(round(reports * 1000.0 / published));
        assertThat(metric(data, "ecology", "revisitRate30d").path("value").asDouble())
                .isEqualTo(round(revisit[0] * 100.0 / revisit[1]));
        assertThat(metric(data, "ecology", "revisitRate30d").path("numerator").asLong())
                .isGreaterThanOrEqualTo(1L);

        // 漏斗：注册 → 实名 → 核验 → 首评。
        JsonNode funnel = data.path("funnel");
        assertThat(funnel.size()).isEqualTo(4);
        assertThat(funnel.get(0).path("stage").asText()).isEqualTo("注册");
        assertThat(funnel.get(0).path("count").asLong()).isEqualTo(2);
        assertThat(funnel.get(1).path("count").asLong()).isEqualTo(1);
        assertThat(funnel.get(2).path("count").asLong()).isEqualTo(1);
        assertThat(funnel.get(3).path("count").asLong()).isEqualTo(1);
        assertThat(funnel.get(1).path("conversionFromPrevious").asDouble()).isEqualTo(50.0);
        assertThat(funnel.get(2).path("conversionFromPrevious").asDouble()).isEqualTo(100.0);
        assertThat(funnel.get(3).path("conversionFromPrevious").asDouble()).isEqualTo(100.0);

        // 14 项指标齐全，且每项都带口径说明。
        assertThat(data.path("metrics").size()).isEqualTo(14);
        assertThat(data.path("metricDefinitions").size()).isEqualTo(14);
        assertThat(metric(data, "quality", "complaintOverturnRate").path("definition").asText())
                .contains("RULED_USER_WIN", "created_at");
    }

    // ------------------------------------------------------------------
    // 北极星：缺 CURRENT 或不足 3 条均为 0
    // ------------------------------------------------------------------

    @Test
    void northstarRequiresThreePublishedAndOneCurrentBadge() throws Exception {
        Actor author = register();
        Instant now = Instant.now();
        String admin = adminToken("CONTENT_ADMIN");
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = today.minusDays(6);

        String qualifiedCity = "北极星达标城" + SEQ.incrementAndGet();
        String qualifiedIndustry = "北极星达标行业" + SEQ.incrementAndGet();
        String qualified = createCompany(qualifiedCity, qualifiedIndustry, "达标公司");
        insertReview(qualified, author, "CURRENT", "PUBLISHED", now);
        insertReview(qualified, author, "FORMER", "PUBLISHED", now);
        insertReview(qualified, author, "FORMER", "PUBLISHED", now);
        JsonNode qualifiedData = dashboard(admin, qualifiedCity, qualifiedIndustry, from, today);
        assertThat(qualifiedData.path("northstar").path("value").asDouble()).isEqualTo(1.0);
        assertThat(qualifiedData.path("northstar").path("denominator").asLong()).isEqualTo(1);

        String missingCity = "北极星缺徽标城" + SEQ.incrementAndGet();
        String missingIndustry = "北极星缺徽标行业" + SEQ.incrementAndGet();
        String missing = createCompany(missingCity, missingIndustry, "缺徽标公司");
        insertReview(missing, author, "FORMER", "PUBLISHED", now);
        insertReview(missing, author, "FORMER", "PUBLISHED", now);
        insertReview(missing, author, null, "PUBLISHED", now);
        JsonNode missingData = dashboard(admin, missingCity, missingIndustry, from, today);
        assertThat(missingData.path("northstar").path("value").asDouble()).isZero();

        String fewCity = "北极星不足城" + SEQ.incrementAndGet();
        String fewIndustry = "北极星不足行业" + SEQ.incrementAndGet();
        String few = createCompany(fewCity, fewIndustry, "不足公司");
        insertReview(few, author, "CURRENT", "PUBLISHED", now);
        insertReview(few, author, "CURRENT", "PUBLISHED", now);
        JsonNode fewData = dashboard(admin, fewCity, fewIndustry, from, today);
        assertThat(fewData.path("northstar").path("value").asDouble()).isZero();
    }

    // ------------------------------------------------------------------
    // 三条红线边界
    // ------------------------------------------------------------------

    @Test
    void complaintOverturnRedLineBoundaryIsThirtyNineAndForty() throws Exception {
        assertComplaintOverturn("投诉红线39", 39, true);
        assertComplaintOverturn("投诉红线40", 40, false);
    }

    private void assertComplaintOverturn(String prefix, int userWins, boolean triggered) throws Exception {
        Actor author = register();
        String city = prefix + "城" + SEQ.incrementAndGet();
        String industry = prefix + "行业" + SEQ.incrementAndGet();
        String uscc = createCompany(city, industry, prefix + "公司");
        Instant now = Instant.now();
        UUID[] reviewIds = new UUID[100];
        for (int i = 0; i < 100; i++) {
            reviewIds[i] = insertReview(uscc, author, "CURRENT", "PUBLISHED", now);
        }
        for (int i = 0; i < 100; i++) {
            insertComplaint(reviewIds[i], uscc,
                    i < userWins ? "RULED_USER_WIN" : "RULED_COMPANY_WIN");
        }
        JsonNode data = dashboard(adminToken("CONTENT_ADMIN"), city, industry,
                LocalDate.now(BUSINESS_ZONE).minusDays(6), LocalDate.now(BUSINESS_ZONE));
        JsonNode metric = metric(data, "quality", "complaintOverturnRate");
        JsonNode line = redLine(data, "complaintOverturnRate");
        assertThat(metric.path("value").asDouble()).isEqualTo((double) userWins);
        assertThat(line.path("threshold").asDouble()).isEqualTo(40.0);
        assertThat(line.path("triggered").asBoolean()).isEqualTo(triggered);
        assertThat(line.path("suggestion").asText()).contains("收紧投诉准入");
    }

    @Test
    void manualRejectRedLineBoundaryIsTwentyFiveAndTwentySix() throws Exception {
        assertManualReject("驳回红线25", 25, false);
        assertManualReject("驳回红线26", 26, true);
    }

    private void assertManualReject(String prefix, int rejects, boolean triggered) throws Exception {
        Actor author = register();
        String city = prefix + "城" + SEQ.incrementAndGet();
        String industry = prefix + "行业" + SEQ.incrementAndGet();
        String uscc = createCompany(city, industry, prefix + "公司");
        Instant now = Instant.now();
        for (int i = 0; i < 100; i++) {
            UUID reviewId = insertReview(uscc, author, "CURRENT", "PENDING_MANUAL", null);
            boolean reject = i < rejects;
            insertAudit(reviewId, reject ? "REJECT" : "APPROVE", "ADMIN",
                    "PENDING_MANUAL", reject ? "REJECTED" : "PUBLISHED",
                    now, reject ? "FALSE_CLAIM" : "MANUAL_CONFIRMED");
        }
        JsonNode data = dashboard(adminToken("CONTENT_ADMIN"), city, industry,
                LocalDate.now(BUSINESS_ZONE).minusDays(6), LocalDate.now(BUSINESS_ZONE));
        JsonNode metric = metric(data, "quality", "manualRejectRate");
        JsonNode line = redLine(data, "manualRejectRate");
        assertThat(metric.path("value").asDouble()).isEqualTo((double) rejects);
        assertThat(line.path("threshold").asDouble()).isEqualTo(25.0);
        assertThat(line.path("triggered").asBoolean()).isEqualTo(triggered);
        assertThat(line.path("suggestion").asText()).contains("提示词");
    }

    @Test
    void searchZeroResultRedLineBoundaryIsTwentyAndTwentyOne() throws Exception {
        assertSearchZero("零结果红线20", 20, false);
        assertSearchZero("零结果红线21", 21, true);
    }

    private void assertSearchZero(String prefix, int zeroCount, boolean triggered) throws Exception {
        String city = prefix + "城" + SEQ.incrementAndGet();
        String industry = prefix + "行业" + SEQ.incrementAndGet();
        for (int i = 0; i < 100; i++) {
            insertSearch(city, industry, i < zeroCount ? 0 : 5);
        }
        JsonNode data = dashboard(adminToken("CONTENT_ADMIN"), city, industry,
                LocalDate.now(BUSINESS_ZONE).minusDays(6), LocalDate.now(BUSINESS_ZONE));
        JsonNode metric = metric(data, "demand", "searchZeroResultRate");
        JsonNode line = redLine(data, "searchZeroResultRate");
        assertThat(metric.path("value").asDouble()).isEqualTo((double) zeroCount);
        assertThat(line.path("threshold").asDouble()).isEqualTo(20.0);
        assertThat(line.path("triggered").asBoolean()).isEqualTo(triggered);
        assertThat(line.path("suggestion").asText()).contains("补档案");
    }

    // ------------------------------------------------------------------
    // 30 天复访与越权
    // ------------------------------------------------------------------

    @Test
    void thirtyDayRevisitCountsVisitorsWithTwoDistinctDays() throws Exception {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Instant end = today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant start = end.minusSeconds(30 * 86400);
        Instant now = Instant.now();

        String repeat = "revisit-repeat-" + UUID.randomUUID();
        insertVisit(repeat, "/company", now);
        insertVisit(repeat, "/company", now.minusSeconds(86400));
        insertVisit("revisit-once-" + UUID.randomUUID(), "/", now);
        insertVisit("revisit-old-" + UUID.randomUUID(), "/", now.minusSeconds(40 * 86400));

        JsonNode data = dashboard(adminToken("CONTENT_ADMIN"), "", "", today.minusDays(6), today);
        long[] manual = manualCounts("""
                        SELECT count(*) FILTER (WHERE day_count >= 2), count(*)
                        FROM (
                            SELECT visitor_id,
                                   count(DISTINCT (created_at AT TIME ZONE 'Asia/Shanghai')::date)
                                       AS day_count
                            FROM visit_event
                            WHERE visitor_id IS NOT NULL AND created_at >= ? AND created_at < ?
                            GROUP BY visitor_id
                        ) t
                        """, Timestamp.from(start), Timestamp.from(end));
        JsonNode metric = metric(data, "ecology", "revisitRate30d");
        assertThat(metric.path("numerator").asLong()).isEqualTo(manual[0]);
        assertThat(metric.path("denominator").asLong()).isEqualTo(manual[1]);
        assertThat(metric.path("value").asDouble())
                .isEqualTo(round(manual[0] * 100.0 / manual[1]));
        assertThat(metric.path("numerator").asLong()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void normalUserCannotAccessDashboard() throws Exception {
        Actor user = register();
        MvcResult result = mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + user.token()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // 缓存：命中 60s 短缓存，refresh=true 失效重算
    // ------------------------------------------------------------------

    @Test
    void dashboardCacheIsHitUntilRefresh() throws Exception {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = today.minusDays(6);
        String city = "看板缓存城" + SEQ.incrementAndGet();
        String industry = "看板缓存行业" + SEQ.incrementAndGet();
        String admin = adminToken("CONTENT_ADMIN");

        createCompany(city, industry, "缓存一公司");
        JsonNode first = dashboardCached(admin, city, industry, from, today);
        assertThat(metric(first, "coverage", "companyCount").path("value").asDouble()).isEqualTo(1.0);
        String cacheKey = redisSupport.key("dashboard:" + from + ":" + today + ":" + city + ":" + industry);
        assertThat(redisSupport.find(cacheKey)).isPresent();
        assertThat(redisSupport.remainingSeconds(cacheKey)).isBetween(55L, 60L);

        createCompany(city, industry, "缓存二公司");
        JsonNode cached = dashboardCached(admin, city, industry, from, today);
        assertThat(metric(cached, "coverage", "companyCount").path("value").asDouble()).isEqualTo(1.0);

        JsonNode refreshed = dashboard(admin, city, industry, from, today);
        assertThat(metric(refreshed, "coverage", "companyCount").path("value").asDouble()).isEqualTo(2.0);
    }

    private static double round(double value) {
        return new java.math.BigDecimal(Double.toString(value))
                .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
