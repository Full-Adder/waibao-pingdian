package com.waibao.pingdian.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 8：人工审核队列、处置、批量与驳回申诉（AC-06 / AC-07 / AC-15）。
 */
class ReviewModerationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(7000);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String CLEAN_CONTENT =
            "公司整体氛围不错，项目安排合理，工资发放也比较准时，沟通顺畅，适合积累经验。";
    private static final String SPECIFIC_CLAIM_CONTENT =
            "公司在项目期间拖欠工资 3 个月，一直没有发放，也没有任何说明，希望尽快处理。";
    private static final String PRIVACY_CONTENT =
            "该公司管理混乱，拖欠工资，联系人手机号13800138000，希望大家谨慎选择。";

    private record Actor(String token, UUID userId) {
    }

    // ------------------------------------------------------------------
    // 基础工具
    // ------------------------------------------------------------------

    private String newUscc() {
        return "91" + String.format("%016d", USCC_SEQ.incrementAndGet());
    }

    private String createCompany() {
        String uscc = newUscc();
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                + "VALUES (?, ?, '杭州', '互联网', 'MANUAL', 'ACTIVE')",
                uscc, "审核测试公司" + uscc.substring(10));
        return uscc;
    }

    private Actor verifiedActor(String uscc) throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        jdbcTemplate.update("""
                        INSERT INTO user_verification
                            (user_id, proof_type, company_uscc, period_start, period_end, status)
                        VALUES (?, 'LABOR_CONTRACT', ?, ?, ?, 'APPROVED')
                        """,
                userId, uscc, now.minusMonths(6).atDay(1), now.plusMonths(1).atEndOfMonth());
        return new Actor(login.path("token").asText(), userId);
    }

    private List<Map<String, Object>> dimensions() {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] codes = {"SALARY", "CONTRACT", "MANAGEMENT"};
        for (String code : codes) {
            list.add(Map.of("code", code, "score", 4));
        }
        return list;
    }

    private Map<String, Object> reviewBody(String uscc, int score, String content) {
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uscc", uscc);
        body.put("periodStart", now.minusMonths(6).toString());
        body.put("periodEnd", now.plusMonths(1).toString());
        body.put("totalScore", score);
        body.put("content", content);
        body.put("dimensions", dimensions());
        return body;
    }

    private JsonNode submitOk(Actor actor, String uscc, int score, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + actor.token())
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewBody(uscc, score, content))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(result);
        assertSuccess(body);
        return body.path("data");
    }

    private MvcResult postJson(String path, String token, Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andReturn();
    }

    private JsonNode queue(String adminToken, String source, String keyword) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/reviews/queue")
                        .param("source", source == null ? "" : source)
                        .param("keyword", keyword == null ? "" : keyword)
                        .param("page", "1")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result).path("data");
    }

    private JsonNode findItem(JsonNode page, String reviewId) {
        for (JsonNode item : page.path("items")) {
            if (reviewId.equals(item.path("reviewId").asText())) {
                return item;
            }
        }
        return null;
    }

    private JsonNode companyRow(String uscc) {
        return objectMapper.valueToTree(jdbcTemplate.queryForMap(
                "SELECT review_count, avg_score, score_visible FROM company WHERE uscc = ?", uscc));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------
    // AC-06：具体指控进队列，通过后发布
    // ------------------------------------------------------------------

    @Test
    void specificClaimEntersQueueAndApprovePublishesWithAggregateAndContribution() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        JsonNode created = submitOk(actor, uscc, 3, SPECIFIC_CLAIM_CONTENT);
        String reviewId = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("PENDING_MANUAL");

        String admin = adminToken("CONTENT_ADMIN");
        JsonNode page = queue(admin, "SPECIFIC_CLAIM", uscc);
        JsonNode item = findItem(page, reviewId);
        assertThat(item).isNotNull();
        assertThat(item.path("source").asText()).isEqualTo("SPECIFIC_CLAIM");
        assertThat(item.path("hasEvidence").asBoolean()).isFalse();
        assertThat(item.path("companyName").asText()).contains("审核测试公司");
        assertThat(item.path("identityAlias").asText()).isNotBlank();
        assertThat(item.path("content").asText()).contains("拖欠工资");
        assertThat(item.path("aiFlags").toString()).contains("SPECIFIC_ALLEGATION");
        assertThat(item.path("rulesVersion").asText()).isNotBlank();
        assertThat(item.path("overdue").asBoolean()).isFalse();

        MvcResult approved = postJson("/api/admin/reviews/" + reviewId + "/approve", admin,
                Map.of("reasonCode", "CONTENT_COMPLIANT", "note", "人工复核内容合规，予以通过"));
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, published_at FROM review WHERE id = ?", UUID.fromString(reviewId));
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("published_at")).isNotNull();
        assertThat(companyRow(uscc).path("review_count").asInt()).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                + "AND reason = 'REVIEW' AND ref_id = ?", actor.userId(), reviewId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT delta FROM contribution_ledger WHERE user_id = ? AND reason = 'REVIEW' AND ref_id = ?",
                        Integer.class, actor.userId(), reviewId))
                .isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class, actor.userId()))
                .isEqualTo(20);

        // 重复处置被拒，贡献值不重复发放。
        MvcResult again = postJson("/api/admin/reviews/" + reviewId + "/approve", admin,
                Map.of("reasonCode", "CONTENT_COMPLIANT", "note", "重复提交"));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                + "AND reason = 'REVIEW' AND ref_id = ?", actor.userId(), reviewId)).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'APPROVE'",
                UUID.fromString(reviewId))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REVIEW_APPROVE' "
                + "AND target_id = ? AND before_hash IS NOT NULL AND after_hash IS NOT NULL",
                reviewId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 队列排序与 overdue
    // ------------------------------------------------------------------

    @Test
    void queueOrdersByWaitingTimeAndMarksOverdue() throws Exception {
        String uscc = createCompany();
        Actor older = verifiedActor(uscc);
        Actor newer = verifiedActor(uscc);
        String olderId = submitOk(older, uscc, 3, SPECIFIC_CLAIM_CONTENT).path("id").asText();
        String newerId = submitOk(newer, uscc, 3, SPECIFIC_CLAIM_CONTENT).path("id").asText();

        jdbcTemplate.update("UPDATE review_audit SET created_at = now() - interval '72 hours' "
                + "WHERE review_id = ? AND to_status = 'PENDING_MANUAL'", UUID.fromString(olderId));
        jdbcTemplate.update("UPDATE review_audit SET created_at = now() - interval '1 hour' "
                + "WHERE review_id = ? AND to_status = 'PENDING_MANUAL'", UUID.fromString(newerId));

        String admin = adminToken("CONTENT_ADMIN");
        JsonNode page = queue(admin, null, uscc);
        assertThat(page.path("total").asInt()).isGreaterThanOrEqualTo(2);
        JsonNode items = page.path("items");
        assertThat(items.get(0).path("reviewId").asText()).isEqualTo(olderId);
        assertThat(items.get(1).path("reviewId").asText()).isEqualTo(newerId);

        JsonNode olderItem = items.get(0);
        assertThat(olderItem.path("overdue").asBoolean()).isTrue();
        assertThat(olderItem.path("waitingHours").asLong()).isGreaterThanOrEqualTo(48);
        assertThat(items.get(1).path("overdue").asBoolean()).isFalse();

        MvcResult detail = mockMvc.perform(get("/api/admin/reviews/queue/" + olderId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailData = body(detail).path("data");
        assertThat(detailData.path("content").asText()).contains("拖欠工资");
        assertThat(detailData.path("aiFlags").toString()).contains("SPECIFIC_ALLEGATION");
        assertThat(detailData.path("rulesVersion").asText()).isNotBlank();
        assertThat(detailData.path("auditHistory").size()).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------
    // AC-07：红线不可申诉，非红线可申诉一次
    // ------------------------------------------------------------------

    @Test
    void redlineCannotAppealAndNonRedlineAppealsOnce() throws Exception {
        String redlineUscc = createCompany();
        Actor redlineActor = verifiedActor(redlineUscc);
        JsonNode redline = submitOk(redlineActor, redlineUscc, 4, PRIVACY_CONTENT);
        assertThat(redline.path("status").asText()).isEqualTo("REJECTED");
        assertThat(redline.path("canAppeal").asBoolean()).isFalse();

        MvcResult redlineAppeal = postJson("/api/reviews/" + redline.path("id").asText() + "/appeal",
                redlineActor.token(), Map.of("statement", "申请复核"));
        assertThat(redlineAppeal.getResponse().getStatus()).isIn(403, 409);

        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        JsonNode pending = submitOk(actor, uscc, 3, SPECIFIC_CLAIM_CONTENT);
        String reviewId = pending.path("id").asText();
        String admin = adminToken("CONTENT_ADMIN");
        MvcResult rejected = postJson("/api/admin/reviews/" + reviewId + "/reject", admin,
                Map.of("reasonCode", "INSUFFICIENT_EVIDENCE", "note", "缺少佐证，先驳回补充材料"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);

        JsonNode mine = body(getWithToken("/api/reviews/mine", actor.token())).path("data");
        JsonNode mineItem = mine.path("items").get(0);
        assertThat(mineItem.path("status").asText()).isEqualTo("REJECTED");
        assertThat(mineItem.path("canAppeal").asBoolean()).isTrue();

        MvcResult appeal = postJson("/api/reviews/" + reviewId + "/appeal",
                actor.token(), Map.of("statement", "已补充在职与工资流水说明"));
        assertThat(appeal.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(appeal).path("data").path("status").asText()).isEqualTo("PENDING_MANUAL");

        JsonNode appealQueue = queue(admin, "APPEAL", uscc);
        assertThat(findItem(appealQueue, reviewId)).isNotNull();

        MvcResult rejectAgain = postJson("/api/admin/reviews/" + reviewId + "/reject", admin,
                Map.of("reasonCode", "INSUFFICIENT_EVIDENCE", "note", "申诉材料仍不充分"));
        assertThat(rejectAgain.getResponse().getStatus()).isEqualTo(200);

        MvcResult secondAppeal = postJson("/api/reviews/" + reviewId + "/appeal",
                actor.token(), Map.of("statement", "再次申诉"));
        assertThat(secondAppeal.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'APPEAL'",
                UUID.fromString(reviewId))).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AC-15：无删除接口、缺理由字段 400
    // ------------------------------------------------------------------

    @Test
    void noDeleteEndpointAndDispositionRequiresReasonAndNote() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = submitOk(actor, uscc, 3, SPECIFIC_CLAIM_CONTENT).path("id").asText();
        String admin = adminToken("CONTENT_ADMIN");

        MvcResult deleted = mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        assertThat(deleted.getResponse().getStatus()).isIn(404, 405);

        MvcResult noReason = postJson("/api/admin/reviews/" + reviewId + "/approve", admin,
                Map.of("note", "只有说明没有原因码"));
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noReason).path("message").asText()).contains("reasonCode");

        MvcResult noNote = postJson("/api/admin/reviews/" + reviewId + "/approve", admin,
                Map.of("reasonCode", "CONTENT_COMPLIANT"));
        assertThat(noNote.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noNote).path("message").asText()).contains("note");

        MvcResult badReason = postJson("/api/admin/reviews/" + reviewId + "/reject", admin,
                Map.of("reasonCode", "NOT_A_REASON", "note", "说明"));
        assertThat(badReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(badReason).path("message").asText()).contains("原因码");

        // 处置未发生，仍为待人工。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM review WHERE id = ?", String.class, UUID.fromString(reviewId)))
                .isEqualTo("PENDING_MANUAL");
    }

    // ------------------------------------------------------------------
    // 批量处置：部分成功
    // ------------------------------------------------------------------

    @Test
    void batchDispositionIsPartialSuccess() throws Exception {
        String uscc = createCompany();
        Actor pendingActor = verifiedActor(uscc);
        Actor publishedActor = verifiedActor(uscc);
        String pendingId = submitOk(pendingActor, uscc, 3, SPECIFIC_CLAIM_CONTENT).path("id").asText();
        String publishedId = submitOk(publishedActor, uscc, 4, CLEAN_CONTENT).path("id").asText();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(publishedId))).isEqualTo("PUBLISHED");

        String admin = adminToken("CONTENT_ADMIN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", List.of(pendingId, publishedId));
        body.put("action", "APPROVE");
        body.put("reasonCode", "CONTENT_COMPLIANT");
        body.put("note", "批量复核通过");
        MvcResult result = postJson("/api/admin/reviews/batch", admin, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(result).path("data");
        assertThat(data.path("total").asInt()).isEqualTo(2);
        assertThat(data.path("succeeded").asInt()).isEqualTo(1);
        assertThat(data.path("failed").asInt()).isEqualTo(1);

        JsonNode results = data.path("results");
        JsonNode pendingResult = null;
        JsonNode publishedResult = null;
        for (JsonNode item : results) {
            if (pendingId.equals(item.path("reviewId").asText())) {
                pendingResult = item;
            } else if (publishedId.equals(item.path("reviewId").asText())) {
                publishedResult = item;
            }
        }
        assertThat(pendingResult.path("success").asBoolean()).isTrue();
        assertThat(pendingResult.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(publishedResult.path("success").asBoolean()).isFalse();
        assertThat(publishedResult.path("code").asInt()).isEqualTo(409);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(pendingId))).isEqualTo("PUBLISHED");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REVIEW_BATCH'"))
                .isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 折叠 / 驳回的可见性与审计
    // ------------------------------------------------------------------

    @Test
    void foldIsPublicButCollapsedAndRejectIsAuthorOnly() throws Exception {
        String uscc = createCompany();
        Actor foldActor = verifiedActor(uscc);
        Actor rejectActor = verifiedActor(uscc);
        String foldId = submitOk(foldActor, uscc, 3, SPECIFIC_CLAIM_CONTENT).path("id").asText();
        String rejectId = submitOk(rejectActor, uscc, 3, SPECIFIC_CLAIM_CONTENT).path("id").asText();
        String admin = adminToken("CONTENT_ADMIN");

        MvcResult folded = postJson("/api/admin/reviews/" + foldId + "/fold", admin,
                Map.of("reasonCode", "VAGUE_CLAIM", "note", "描述笼统且无佐证，折叠展示"));
        assertThat(folded.getResponse().getStatus()).isEqualTo(200);
        JsonNode foldView = body(mockMvc.perform(get("/api/reviews/" + foldId))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(foldView.path("status").asText()).isEqualTo("FOLDED");
        assertThat(foldView.path("collapsed").asBoolean()).isTrue();
        assertThat(foldView.path("collapseNote").asText()).contains("折叠");
        JsonNode publicList = body(mockMvc.perform(get("/api/companies/" + uscc + "/reviews"))
                .andExpect(status().isOk()).andReturn()).path("data");
        boolean visible = false;
        for (JsonNode item : publicList.path("items")) {
            if (foldId.equals(item.path("id").asText())) {
                visible = true;
            }
        }
        assertThat(visible).isTrue();

        MvcResult rejected = postJson("/api/admin/reviews/" + rejectId + "/reject", admin,
                Map.of("reasonCode", "INSUFFICIENT_EVIDENCE", "note", "缺少佐证，驳回"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/api/reviews/" + rejectId)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        JsonNode authorView = body(getWithToken("/api/reviews/" + rejectId, rejectActor.token())).path("data");
        assertThat(authorView.path("status").asText()).isEqualTo("REJECTED");
        assertThat(authorView.path("rejectReasonCode").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");

        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REVIEW_FOLD' AND target_id = ?",
                foldId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'REVIEW_REJECT' AND target_id = ?",
                rejectId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'FOLD'",
                UUID.fromString(foldId))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'REJECT'",
                UUID.fromString(rejectId))).isEqualTo(1);
    }

    @Test
    void reasonTemplatesContainAtLeastFourPerAction() throws Exception {
        String admin = adminToken("CONTENT_ADMIN");
        JsonNode data = body(mockMvc.perform(get("/api/admin/reviews/reason-templates")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(data.path("approve").size()).isGreaterThanOrEqualTo(4);
        assertThat(data.path("fold").size()).isGreaterThanOrEqualTo(4);
        assertThat(data.path("reject").size()).isGreaterThanOrEqualTo(4);
        assertThat(data.path("reject").get(0).path("reasonCode").asText()).isNotBlank();
    }
}
