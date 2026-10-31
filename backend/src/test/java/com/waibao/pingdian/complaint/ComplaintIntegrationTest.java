package com.waibao.pingdian.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 9：企业投诉、主体核验、双向举证、裁定、双人复核与企业回应。
 */
class ComplaintIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(8000);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String VALID_ID = "110101199001010015";
    private static final String CLEAN_CONTENT =
            "公司整体氛围不错，项目安排合理，工资发放也比较准时，沟通顺畅，适合积累经验。";

    private record Actor(String token, UUID userId) {
    }

    private record ComplaintContext(String uscc, String reviewId, String complaintId, Actor actor) {
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
                uscc, "投诉测试公司" + uscc.substring(10));
        return uscc;
    }

    private Actor verifiedActor(String uscc) throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        String token = login.path("token").asText();
        mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", VALID_ID))))
                .andExpect(status().isOk());
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        jdbcTemplate.update("""
                        INSERT INTO user_verification
                            (user_id, proof_type, company_uscc, period_start, period_end, status)
                        VALUES (?, 'LABOR_CONTRACT', ?, ?, ?, 'APPROVED')
                        """,
                userId, uscc, now.minusMonths(6).atDay(1), now.plusMonths(1).atEndOfMonth());
        return new Actor(token, userId);
    }

    private List<Map<String, Object>> dimensions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String code : List.of("SALARY", "CONTRACT", "MANAGEMENT")) {
            list.add(Map.of("code", code, "score", 4));
        }
        return list;
    }

    private String publishReview(Actor actor, String uscc, String content) throws Exception {
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uscc", uscc);
        body.put("periodStart", now.minusMonths(6).toString());
        body.put("periodEnd", now.plusMonths(1).toString());
        body.put("totalScore", 4);
        body.put("content", content);
        body.put("dimensions", dimensions());
        MvcResult result = mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + actor.token())
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = body(result).path("data");
        assertThat(data.path("status").asText()).isEqualTo("PUBLISHED");
        return data.path("id").asText();
    }

    private MockMultipartFile material(String field, String name) {
        return new MockMultipartFile(field, name, "application/pdf",
                "test-material-content".getBytes(StandardCharsets.UTF_8));
    }

    private MvcResult appealRaw(String uscc, String reviewId, String contact,
                                boolean amountFlag, boolean safetyFlag, boolean withFiles) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/appeals");
        builder.param("uscc", uscc)
                .param("reviewId", reviewId)
                .param("contact", contact);
        if (amountFlag) {
            builder.param("amountFlag", "true");
        }
        if (safetyFlag) {
            builder.param("safetyFlag", "true");
        }
        if (withFiles) {
            builder.file(material("licenseFile", "license.pdf"))
                    .file(material("letterFile", "letter.pdf"));
        }
        return mockMvc.perform(builder).andReturn();
    }

    private String appealOk(String uscc, String reviewId, boolean amountFlag, boolean safetyFlag)
            throws Exception {
        MvcResult result = appealRaw(uscc, reviewId, "13800138000", amountFlag, safetyFlag, true);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = body(result).path("data");
        assertThat(data.path("status").asText()).isEqualTo("PENDING_VERIFY");
        return data.path("complaintId").asText();
    }

    private void verify(String adminToken, String complaintId, boolean verified, String note) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/complaints/" + complaintId + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("verified", verified, "note", note))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult rule(String adminToken, String complaintId, Map<String, Object> payload)
            throws Exception {
        return mockMvc.perform(post("/api/admin/complaints/" + complaintId + "/ruling")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andReturn();
    }

    private Map<String, Object> rulingBody(String result, String enterpriseGrade, String userGrade,
                                           String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        body.put("enterpriseGrade", enterpriseGrade);
        body.put("userGrade", userGrade);
        body.put("reasonText", reason);
        body.put("citedMaterialIds", List.of());
        body.put("ruleClause", "PRD 8.5 判定矩阵");
        return body;
    }

    private ComplaintContext prepareVerifiedComplaint() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = publishReview(actor, uscc, CLEAN_CONTENT);
        String complaintId = appealOk(uscc, reviewId, false, false);
        verify(adminToken("CONTENT_ADMIN"), complaintId, true, "USCC 与档案一致");
        return new ComplaintContext(uscc, reviewId, complaintId, actor);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------
    // AC-09：未上传执照 / 未核验 → 不改状态
    // ------------------------------------------------------------------

    @Test
    void unverifiedAppealNeverChangesReviewStatus() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = publishReview(actor, uscc, CLEAN_CONTENT);
        String admin = adminToken("CONTENT_ADMIN");

        MvcResult missingLicense = appealRaw(uscc, reviewId, "13800138000", false, false, false);
        assertThat(missingLicense.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(reviewId))).isEqualTo("PUBLISHED");
        assertThat(count("SELECT count(*) FROM complaint WHERE review_id = ?",
                UUID.fromString(reviewId))).isZero();

        String complaintId = appealOk(uscc, reviewId, false, false);
        verify(admin, complaintId, false, "USCC 与档案不一致");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM complaint WHERE id = ?",
                String.class, UUID.fromString(complaintId))).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject("SELECT subject_verified FROM complaint WHERE id = ?",
                Boolean.class, UUID.fromString(complaintId))).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(reviewId))).isEqualTo("PUBLISHED");
    }

    // ------------------------------------------------------------------
    // AC-10：核验通过 → DISPUTED、降权、移出榜单、48h 倒计时
    // ------------------------------------------------------------------

    @Test
    void verifiedAppealTransfersReviewToDisputedWithDeadlineAndAggregate() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        String admin = adminToken("CONTENT_ADMIN");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, ever_disputed, dispute_deadline FROM review WHERE id = ?",
                UUID.fromString(context.reviewId()));
        assertThat(row.get("status")).isEqualTo("DISPUTED");
        assertThat(row.get("ever_disputed")).isEqualTo(true);
        assertThat(row.get("dispute_deadline")).isNotNull();

        Number seconds = jdbcTemplate.queryForObject(
                "SELECT extract(epoch FROM (deadline - now())) FROM complaint WHERE id = ?",
                Number.class, UUID.fromString(context.complaintId()));
        assertThat(seconds.longValue()).isBetween(47L * 3600, 49L * 3600);

        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM company WHERE uscc = ?",
                Integer.class, context.uscc())).isZero();

        JsonNode publicList = body(mockMvc.perform(get("/api/companies/" + context.uscc() + "/reviews"))
                .andExpect(status().isOk()).andReturn()).path("data");
        JsonNode item = publicList.path("items").get(0);
        assertThat(item.path("status").asText()).isEqualTo("DISPUTED");
        assertThat(item.path("disputed").asBoolean()).isTrue();

        JsonNode detail = body(mockMvc.perform(get("/api/admin/complaints/" + context.complaintId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(detail.path("status").asText()).isEqualTo("EVIDENCE_COLLECTING");
        assertThat(detail.path("review").path("content").asText()).contains("氛围不错");
        assertThat(detail.path("materials").size()).isGreaterThanOrEqualTo(2);
        assertThat(detail.path("remainingSeconds").asLong()).isGreaterThan(47L * 3600);

        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'DISPUTE_STARTED' "
                + "AND channel = 'BOTH' AND ref_id = ?", context.actor().userId(), context.complaintId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'COMPLAINT_VERIFY_APPROVE' "
                + "AND ticket_id = ? AND target_id = ?", context.complaintId(), context.complaintId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'COMPLAINT_VERIFIED'",
                UUID.fromString(context.reviewId()))).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AC-12：二次投诉 409 + 异常审计
    // ------------------------------------------------------------------

    @Test
    void duplicateComplaintRejectedAndAnomalyAudited() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = publishReview(actor, uscc, CLEAN_CONTENT);
        String complaintId = appealOk(uscc, reviewId, false, false);

        MvcResult second = appealRaw(uscc, reviewId, "13800138000", false, false, true);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(second).path("message").asText()).contains("该评价已有投诉记录");
        assertThat(count("SELECT count(*) FROM complaint WHERE review_id = ?",
                UUID.fromString(reviewId))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                + "AND action = 'COMPLAINT_DUPLICATE_ANOMALY'", UUID.fromString(reviewId))).isEqualTo(1);
        assertThat(complaintId).isNotBlank();
    }

    // ------------------------------------------------------------------
    // AC-11：双方均无证据 → INSUFFICIENT → FOLDED
    // ------------------------------------------------------------------

    @Test
    void bothSidesNoEvidenceRulingFoldsReview() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        String admin = adminToken("CONTENT_ADMIN");

        MvcResult ruled = rule(admin, context.complaintId(),
                rulingBody("INSUFFICIENT", "NONE", "NONE", "双方均未举证，证据不足"));
        assertThat(ruled.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(ruled).path("data");
        assertThat(data.path("result").asText()).isEqualTo("INSUFFICIENT");
        assertThat(data.path("pendingDualReview").asBoolean()).isFalse();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM complaint WHERE id = ?",
                String.class, UUID.fromString(context.complaintId()))).isEqualTo("RULED_INSUFFICIENT");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(context.reviewId()))).isEqualTo("FOLDED");

        JsonNode reviewView = body(mockMvc.perform(get("/api/reviews/" + context.reviewId()))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(reviewView.path("collapsed").asBoolean()).isTrue();
        assertThat(reviewView.path("collapseNote").asText()).contains("折叠");

        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'RULING_FINAL' "
                + "AND target_id = ? AND before_hash IS NOT NULL AND after_hash IS NOT NULL",
                context.complaintId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'RULING_RESULT' "
                + "AND ref_id = ?", context.actor().userId(), context.complaintId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 判定矩阵五个分支
    // ------------------------------------------------------------------

    @Test
    void rulingMatrixCoversAllFiveBranches() throws Exception {
        String admin = adminToken("CONTENT_ADMIN");

        // 1) 企业 A，用户 C → 仅 COMPANY_WIN
        ComplaintContext first = prepareVerifiedComplaint();
        MvcResult wrong = rule(admin, first.complaintId(),
                rulingBody("USER_WIN", "A", "C", "企业强证据，用户仅弱证据"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(wrong).path("message").asText()).contains("矩阵");
        MvcResult companyWin = rule(admin, first.complaintId(),
                rulingBody("COMPANY_WIN", "A", "C", "企业银行流水清晰，用户仅文字描述"));
        assertThat(companyWin.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(first.reviewId()))).isEqualTo("REMOVED");

        // 2) 用户 A，企业 C → 仅 USER_WIN
        ComplaintContext second = prepareVerifiedComplaint();
        MvcResult userWin = rule(admin, second.complaintId(),
                rulingBody("USER_WIN", "C", "A", "用户有盖章合同与流水，企业仅文字否认"));
        assertThat(userWin.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(second.reviewId()))).isEqualTo("PUBLISHED");

        // 3) 双方同级 B/B → 允许人工选择，默认倾向保留（此处选 USER_WIN）
        ComplaintContext third = prepareVerifiedComplaint();
        MvcResult sameLevel = rule(admin, third.complaintId(),
                rulingBody("USER_WIN", "B", "B", "双方均为中等证据，逐条核对后倾向保留"));
        assertThat(sameLevel.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(third.reviewId()))).isEqualTo("PUBLISHED");

        // 4) 双方均 C → 仅 INSUFFICIENT
        ComplaintContext fourth = prepareVerifiedComplaint();
        MvcResult insufficient = rule(admin, fourth.complaintId(),
                rulingBody("INSUFFICIENT", "C", "C", "双方证据均为弱证据，争议未决"));
        assertThat(insufficient.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(fourth.reviewId()))).isEqualTo("FOLDED");

        // 5) 一方 A、另一方 NONE → 提交方胜
        ComplaintContext fifth = prepareVerifiedComplaint();
        MvcResult submitterWin = rule(admin, fifth.complaintId(),
                rulingBody("COMPANY_WIN", "A", "NONE", "企业提交机构可验证材料，用户未提交"));
        assertThat(submitterWin.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(fifth.reviewId()))).isEqualTo("REMOVED");

        ComplaintContext sixth = prepareVerifiedComplaint();
        MvcResult userOnly = rule(admin, sixth.complaintId(),
                rulingBody("USER_WIN", "NONE", "A", "用户提交仲裁文书，企业未提交"));
        assertThat(userOnly.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // AC-20：下架发布 >30 天点评需双人复核
    // ------------------------------------------------------------------

    @Test
    void removalOfOldPublishedReviewRequiresSecondSuperAdmin() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = publishReview(actor, uscc, CLEAN_CONTENT);
        jdbcTemplate.update("UPDATE review SET published_at = now() - interval '40 days' WHERE id = ?",
                UUID.fromString(reviewId));

        String complaintId = appealOk(uscc, reviewId, false, false);
        String firstSuper = adminToken("SUPER_ADMIN");
        verify(firstSuper, complaintId, true, "USCC 与档案一致");

        MvcResult proposed = rule(firstSuper, complaintId,
                rulingBody("COMPANY_WIN", "A", "NONE", "企业材料可验证，拟下架发布超 30 天点评"));
        assertThat(proposed.getResponse().getStatus()).isEqualTo(202);
        JsonNode proposal = body(proposed).path("data");
        assertThat(proposal.path("pendingDualReview").asBoolean()).isTrue();
        assertThat(proposal.path("status").asText()).isEqualTo("PENDING_RULING");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(reviewId))).isEqualTo("DISPUTED");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'RULING_PROPOSED' "
                + "AND target_id = ?", complaintId)).isEqualTo(1);

        MvcResult sameAdmin = rule(firstSuper, complaintId,
                rulingBody("COMPANY_WIN", "A", "NONE", "同一人再次确认"));
        assertThat(sameAdmin.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(sameAdmin).path("message").asText()).contains("同一名管理员");

        String secondSuper = adminToken("SUPER_ADMIN");
        MvcResult confirmed = rule(secondSuper, complaintId,
                rulingBody("COMPANY_WIN", "A", "NONE", "另一名超管复核确认"));
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(reviewId))).isEqualTo("REMOVED");
        assertThat(jdbcTemplate.queryForObject("SELECT dual_review_required FROM complaint WHERE id = ?",
                Boolean.class, UUID.fromString(complaintId))).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT dual_review_by FROM complaint WHERE id = ?",
                UUID.class, UUID.fromString(complaintId))).isNotNull();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'RULING_FINAL' "
                + "AND target_id = ? AND before_hash IS NOT NULL AND after_hash IS NOT NULL",
                complaintId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 企业回应：联系方式过滤、≤500、审核后公开、未审核不公开
    // ------------------------------------------------------------------

    @Test
    void enterpriseResponseFiltersContactsAndPublishesOnlyAfterApproval() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        String admin = adminToken("CONTENT_ADMIN");

        String phoneContent = "我方已核实，请联系手机号 13800138000 沟通。";
        MvcResult phone = submitResponse(context.uscc(), context.complaintId(), phoneContent);
        assertThat(phone.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(phone).path("message").asText()).contains("联系方式");

        String longContent = "好".repeat(600);
        MvcResult tooLong = submitResponse(context.uscc(), context.complaintId(), longContent);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooLong).path("message").asText()).contains("500");

        MvcResult ok = submitResponse(context.uscc(), context.complaintId(),
                "我方已按合同足额发放工资，项目安排与评价描述存在差异，相关凭证已提交平台核验。");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM enterprise_response WHERE complaint_id = ?",
                String.class, UUID.fromString(context.complaintId()))).isEqualTo("PENDING");

        JsonNode beforeApprove = body(mockMvc.perform(get("/api/companies/" + context.uscc()
                        + "/responses").param("reviewId", context.reviewId()))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(beforeApprove.size()).isZero();

        JsonNode pendingList = body(mockMvc.perform(get("/api/admin/responses")
                        .param("status", "PENDING").param("page", "1").param("size", "50")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn()).path("data");
        String responseId = null;
        for (JsonNode item : pendingList.path("items")) {
            if (context.complaintId().equals(item.path("complaintId").asText())) {
                responseId = item.path("id").asText();
            }
        }
        assertThat(responseId).isNotNull();

        mockMvc.perform(post("/api/admin/responses/" + responseId + "/approve")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        JsonNode afterApprove = body(mockMvc.perform(get("/api/companies/" + context.uscc()
                        + "/responses").param("reviewId", context.reviewId()))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(afterApprove.size()).isEqualTo(1);
        assertThat(afterApprove.get(0).path("label").asText()).isEqualTo("企业回应");
        assertThat(afterApprove.get(0).path("content").asText()).contains("足额发放工资");

        JsonNode reviewView = body(mockMvc.perform(get("/api/reviews/" + context.reviewId()))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(reviewView.path("enterpriseResponse").path("content").asText())
                .contains("足额发放工资");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(context.reviewId()))).isEqualTo("DISPUTED");

        // 驳回必须填写理由。
        MvcResult second = submitResponse(context.uscc(), context.complaintId(),
                "我方补充说明：项目结算与考勤记录均可核验。");
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        UUID secondId = jdbcTemplate.queryForObject(
                "SELECT id FROM enterprise_response WHERE complaint_id = ? AND status = 'PENDING'",
                UUID.class, UUID.fromString(context.complaintId()));
        MvcResult noReason = mockMvc.perform(post("/api/admin/responses/" + secondId + "/reject")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        MvcResult rejected = mockMvc.perform(post("/api/admin/responses/" + secondId + "/reject")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "内容与事实不符"))))
                .andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);

        // 企业不可删除。
        MvcResult deleted = mockMvc.perform(delete("/api/admin/responses/" + responseId)
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        assertThat(deleted.getResponse().getStatus()).isIn(404, 405);
    }

    private MvcResult submitResponse(String uscc, String complaintId, String content) throws Exception {
        return mockMvc.perform(post("/api/companies/" + uscc + "/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ticketId", complaintId, "content", content))))
                .andReturn();
    }

    // ------------------------------------------------------------------
    // 材料访问：必须匹配工单号且仅管理员
    // ------------------------------------------------------------------

    @Test
    void complaintMaterialRequiresMatchingTicketAndAdminRole() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        String admin = adminToken("CONTENT_ADMIN");

        MvcResult uploaded = mockMvc.perform(multipart(
                        "/api/admin/complaints/" + context.complaintId() + "/evidence")
                        .file(material("file", "evidence.pdf"))
                        .param("party", "ENTERPRISE")
                        .param("grade", "A")
                        .param("note", "银行流水与考勤导出")
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(200);
        JsonNode evidence = body(uploaded).path("data");
        String evidenceId = evidence.path("id").asText();
        assertThat(evidence.path("grade").asText()).isEqualTo("A");

        MvcResult ok = mockMvc.perform(get("/api/admin/materials/COMPLAINT_EVIDENCE/"
                        + evidenceId + "/url")
                        .param("ticketId", context.complaintId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(ok).path("data").path("url").asText()).contains("sig=");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'MATERIAL_READ' "
                + "AND target_id = ?", evidenceId)).isEqualTo(1);

        MvcResult wrongTicket = mockMvc.perform(get("/api/admin/materials/COMPLAINT_EVIDENCE/"
                        + evidenceId + "/url")
                        .param("ticketId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        assertThat(wrongTicket.getResponse().getStatus()).isEqualTo(403);

        MvcResult asUser = mockMvc.perform(get("/api/admin/materials/COMPLAINT_EVIDENCE/"
                        + evidenceId + "/url")
                        .param("ticketId", context.complaintId())
                        .header("Authorization", "Bearer " + context.actor().token()))
                .andReturn();
        assertThat(asUser.getResponse().getStatus()).isEqualTo(403);

        MvcResult license = mockMvc.perform(get("/api/admin/materials/COMPLAINT_LICENSE/"
                        + context.complaintId() + "/url")
                        .param("ticketId", context.complaintId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(license).path("data").path("url").asText()).contains("sig=");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT enterprise_grade FROM complaint WHERE id = ?",
                String.class, UUID.fromString(context.complaintId()))).isEqualTo("A");
    }

    // ------------------------------------------------------------------
    // 作者举证与我的投诉倒计时
    // ------------------------------------------------------------------

    @Test
    void authorEvidenceAndMineShowsCountdown() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();

        MvcResult upload = mockMvc.perform(multipart(
                        "/api/complaints/" + context.complaintId() + "/evidence")
                        .file(material("file", "user-evidence.pdf"))
                        .param("grade", "B")
                        .param("note", "工资条与聊天记录")
                        .header("Authorization", "Bearer " + context.actor().token()))
                .andReturn();
        assertThat(upload.getResponse().getStatus()).isEqualTo(200);

        JsonNode mine = body(getWithToken("/api/complaints/mine/" + context.reviewId(),
                context.actor().token())).path("data");
        assertThat(mine.path("status").asText()).isEqualTo("EVIDENCE_COLLECTING");
        assertThat(mine.path("userEvidenceSubmitted").asBoolean()).isTrue();
        assertThat(mine.path("enterpriseEvidenceSubmitted").asBoolean()).isFalse();
        assertThat(mine.path("remainingSeconds").asLong()).isGreaterThan(47L * 3600);
        assertThat(mine.path("userEvidenceNote").asText()).contains("工资条");

        jdbcTemplate.update("UPDATE complaint SET deadline = now() - interval '1 hour' WHERE id = ?",
                UUID.fromString(context.complaintId()));
        MvcResult late = mockMvc.perform(multipart(
                        "/api/complaints/" + context.complaintId() + "/evidence")
                        .file(material("file", "late.pdf"))
                        .header("Authorization", "Bearer " + context.actor().token()))
                .andReturn();
        assertThat(late.getResponse().getStatus()).isEqualTo(403);

        JsonNode overdue = body(getWithToken("/api/complaints/mine/" + context.reviewId(),
                context.actor().token())).path("data");
        assertThat(overdue.path("overdue").asBoolean()).isTrue();
        assertThat(overdue.path("remainingSeconds").asLong()).isZero();
    }

    // ------------------------------------------------------------------
    // 黑名单
    // ------------------------------------------------------------------

    @Test
    void blocklistRejectsAppealAndRequiresSuperAdmin() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = publishReview(actor, uscc, CLEAN_CONTENT);

        MvcResult denied = mockMvc.perform(post("/api/admin/complaints/blocklist")
                        .header("Authorization", "Bearer " + adminToken("CONTENT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("uscc", uscc, "reason", "伪造执照投诉"))))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);

        MvcResult added = mockMvc.perform(post("/api/admin/complaints/blocklist")
                        .header("Authorization", "Bearer " + adminToken("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("uscc", uscc, "reason", "伪造执照投诉"))))
                .andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM complaint_blocklist WHERE uscc = ?", uscc)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'COMPLAINT_BLOCKLIST_ADD' "
                + "AND target_id = ?", uscc)).isEqualTo(1);

        MvcResult blocked = appealRaw(uscc, reviewId, "13800138000", false, false, true);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(blocked).path("message").asText()).contains("限制提交投诉");
    }
}
