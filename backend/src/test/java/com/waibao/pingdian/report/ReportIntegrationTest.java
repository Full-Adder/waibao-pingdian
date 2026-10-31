package com.waibao.pingdian.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 10：举报限频、阈值自动进人工、有效举报贡献值与幂等（R15）。
 */
class ReportIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(12000);
    private static final String REVIEW_CONTENT = "公司项目安排比较混乱，沟通效率低，希望后续改进管理流程。";

    private record Actor(String token, UUID userId) {
    }

    private String newUscc() {
        return "93" + String.format("%016d", USCC_SEQ.incrementAndGet());
    }

    private String createCompany() {
        String uscc = newUscc();
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                        + "VALUES (?, ?, '杭州', '互联网', 'MANUAL', 'ACTIVE')",
                uscc, "举报测试公司" + uscc.substring(12));
        return uscc;
    }

    private Actor registerActor() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        return new Actor(login.path("token").asText(), userId);
    }

    private UUID identityOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ?", UUID.class, userId);
    }

    private UUID createReview(UUID authorId, String uscc) {
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review
                            (id, uscc, user_id, identity_id, verification_badge, total_score,
                             content, status, published_at, ever_disputed)
                        VALUES (?, ?, ?, ?, 'CURRENT', 3, ?, 'PUBLISHED', now(), false)
                        """,
                reviewId, uscc, authorId, identityOf(authorId), REVIEW_CONTENT);
        return reviewId;
    }

    private MvcResult submitReport(String token, String targetType, String targetId,
                                   String reasonCode, String detail) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("reasonCode", reasonCode);
        if (detail != null) {
            body.put("detail", detail);
        }
        return mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode adminReports(String adminToken, String status) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/reports")
                        .param("status", status)
                        .param("page", "1")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result).path("data");
    }

    private JsonNode findReport(JsonNode page, String reportId) {
        for (JsonNode item : page.path("items")) {
            if (reportId.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------
    // 24h 同一对象限报
    // ------------------------------------------------------------------

    @Test
    void secondReportWithin24HoursReturns429WithAvailableTime() throws Exception {
        Actor actor = registerActor();
        String uscc = createCompany();

        MvcResult first = submitReport(actor.token(), "COMPANY", uscc, "SPAM", "页面存在重复推广内容");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(first).path("data").path("reportId").asText()).isNotBlank();

        MvcResult second = submitReport(actor.token(), "COMPANY", uscc, "SPAM", "再次举报");
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        JsonNode body = body(second);
        assertThat(body.path("message").asText()).contains("24");
        assertThat(body.path("data").path("availableAt").asText()).isNotBlank();
        assertThat(body.path("data").path("remainingSeconds").asLong()).isPositive();
        assertThat(count("SELECT count(*) FROM report WHERE user_id = ? AND target_type = 'COMPANY'",
                actor.userId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 点评被 5 个不同账号举报 → 标记进人工
    // ------------------------------------------------------------------

    @Test
    void fiveDistinctReportersMarkReviewThresholdWithoutChangingReviewStatus() throws Exception {
        Actor author = registerActor();
        String uscc = createCompany();
        UUID reviewId = createReview(author.userId(), uscc);

        String lastReportId = null;
        boolean thresholdReached = false;
        for (int i = 0; i < 5; i++) {
            Actor reporter = registerActor();
            MvcResult result = submitReport(reporter.token(), "REVIEW", reviewId.toString(),
                    "FALSE_INFO", "该评价描述的事实与公开信息不符");
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode data = body(result).path("data");
            lastReportId = data.path("reportId").asText();
            thresholdReached = data.path("thresholdReached").asBoolean();
        }
        assertThat(thresholdReached).isTrue();
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                + "AND stage = 'REPORT' AND action = 'REPORT_THRESHOLD'", reviewId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, reviewId)).isEqualTo("PUBLISHED");
        assertThat(count("SELECT count(distinct user_id) FROM report "
                + "WHERE target_type = 'REVIEW' AND target_id = ?", reviewId)).isEqualTo(5);

        String admin = adminToken("CONTENT_ADMIN");
        JsonNode page = adminReports(admin, "PENDING");
        JsonNode item = findReport(page, lastReportId);
        assertThat(item).isNotNull();
        assertThat(item.path("thresholdReached").asBoolean()).isTrue();
        assertThat(item.path("targetType").asText()).isEqualTo("REVIEW");
        assertThat(item.path("reasonCode").asText()).isEqualTo("FALSE_INFO");
    }

    // ------------------------------------------------------------------
    // 公司被 5 个不同账号举报 → admin_audit_log
    // ------------------------------------------------------------------

    @Test
    void fiveDistinctReportersMarkCompanyThresholdInAdminAudit() throws Exception {
        String uscc = createCompany();
        String lastReportId = null;
        for (int i = 0; i < 5; i++) {
            Actor reporter = registerActor();
            MvcResult result = submitReport(reporter.token(), "COMPANY", uscc,
                    "ADVERTISING", "公司页面存在广告引流信息");
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            lastReportId = body(result).path("data").path("reportId").asText();
        }
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REPORT_THRESHOLD' "
                + "AND target_type = 'COMPANY'")).isEqualTo(1);

        String admin = adminToken("CONTENT_ADMIN");
        JsonNode page = adminReports(admin, "PENDING");
        JsonNode item = findReport(page, lastReportId);
        assertThat(item).isNotNull();
        assertThat(item.path("thresholdReached").asBoolean()).isTrue();
        assertThat(item.path("targetType").asText()).isEqualTo("COMPANY");
    }

    // ------------------------------------------------------------------
    // 有效举报 +20 幂等；无效举报不加分
    // ------------------------------------------------------------------

    @Test
    void validReportAwardsTwentyIdempotentlyAndInvalidReportAddsNothing() throws Exception {
        Actor author = registerActor();
        String uscc = createCompany();
        UUID reviewId = createReview(author.userId(), uscc);
        String admin = adminToken("CONTENT_ADMIN");

        Actor validReporter = registerActor();
        String validReportId = body(submitReport(validReporter.token(), "REVIEW",
                reviewId.toString(), "PRIVACY", "评价中泄露了个人信息"))
                .path("data").path("reportId").asText();
        MvcResult resolved = mockMvc.perform(post("/api/admin/reports/" + validReportId + "/resolve")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("result", "VALID", "note", "核实为有效举报"))))
                .andReturn();
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM report WHERE id = ?",
                String.class, UUID.fromString(validReportId))).isEqualTo("VALID");
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                        + "AND reason = 'VALID_REPORT' AND ref_id = ?",
                validReporter.userId(), validReportId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT delta FROM contribution_ledger "
                        + "WHERE user_id = ? AND reason = 'VALID_REPORT' AND ref_id = ?",
                Integer.class, validReporter.userId(), validReportId)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("SELECT contribution_score FROM user_account WHERE id = ?",
                Integer.class, validReporter.userId())).isEqualTo(20);
        // 举报不直接改变点评状态。
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, reviewId)).isEqualTo("PUBLISHED");

        MvcResult again = mockMvc.perform(post("/api/admin/reports/" + validReportId + "/resolve")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("result", "VALID", "note", "重复处置"))))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                        + "AND reason = 'VALID_REPORT' AND ref_id = ?",
                validReporter.userId(), validReportId)).isEqualTo(1);

        Actor invalidReporter = registerActor();
        String invalidReportId = body(submitReport(invalidReporter.token(), "REVIEW",
                reviewId.toString(), "OTHER", "主观不认同该评价"))
                .path("data").path("reportId").asText();
        MvcResult invalid = mockMvc.perform(post("/api/admin/reports/" + invalidReportId + "/resolve")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("result", "INVALID", "note", "理由不成立"))))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM report WHERE id = ?",
                String.class, UUID.fromString(invalidReportId))).isEqualTo("INVALID");
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                        + "AND reason = 'VALID_REPORT' AND ref_id = ?",
                invalidReporter.userId(), invalidReportId)).isZero();

        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REPORT_RESOLVE' "
                + "AND target_id = ?", validReportId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REPORT_RESOLVE' "
                + "AND target_id = ?", invalidReportId)).isEqualTo(1);
    }
}
