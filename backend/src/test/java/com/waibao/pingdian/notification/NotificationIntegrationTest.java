package com.waibao.pingdian.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.complaint.service.ComplaintDeadlineJob;
import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.notification.domain.Notification;
import com.waibao.pingdian.notification.domain.NotificationChannel;
import com.waibao.pingdian.notification.domain.NotificationType;
import com.waibao.pingdian.notification.repo.NotificationRepository;
import com.waibao.pingdian.notification.service.NotificationRetryJob;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.provider.SmsProvider;
import com.waibao.pingdian.provider.mock.MockSmsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 11：时限通知、通知中心、短信重试与举证倒计时调度（AC-19 / AC-11）。
 */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(9000);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String VALID_ID = "110101199001010015";
    private static final String CLEAN_CONTENT =
            "公司整体氛围不错，项目安排合理，工资发放也比较准时，沟通顺畅，适合积累经验。";
    private static final String SPECIFIC_CLAIM_CONTENT =
            "公司在项目期间拖欠工资 3 个月，一直没有发放，也没有任何说明，希望尽快处理。";

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRetryJob notificationRetryJob;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ComplaintDeadlineJob complaintDeadlineJob;

    @Autowired
    private SmsProvider smsProvider;

    @Autowired
    private ProvidersProperties providersProperties;

    private record Actor(String token, UUID userId) {
    }

    private record ComplaintContext(String uscc, String reviewId, String complaintId, Actor actor) {
    }

    @AfterEach
    void restoreSmsProvider() {
        providersProperties.getMock().getSms().setFailFirstAttempts(0);
        mockSms().resetNotificationFailureCounter();
    }

    private MockSmsProvider mockSms() {
        return (MockSmsProvider) smsProvider;
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
                uscc, "通知测试公司" + uscc.substring(10));
        return uscc;
    }

    private Actor registerActor() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        return new Actor(login.path("token").asText(),
                UUID.fromString(login.path("user").path("id").asText()));
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

    private MockMultipartFile material(String name) {
        return new MockMultipartFile("file", name, "application/pdf",
                "test-material-content".getBytes(StandardCharsets.UTF_8));
    }

    private String appealOk(String uscc, String reviewId) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/appeals");
        builder.param("uscc", uscc).param("reviewId", reviewId).param("contact", "13800138000");
        builder.file(new MockMultipartFile("licenseFile", "license.pdf", "application/pdf",
                        "license".getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("letterFile", "letter.pdf", "application/pdf",
                        "letter".getBytes(StandardCharsets.UTF_8)));
        MvcResult result = mockMvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result).path("data").path("complaintId").asText();
    }

    private void verify(String adminToken, String complaintId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/complaints/" + complaintId + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("verified", true, "note", "USCC 与档案一致"))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private ComplaintContext prepareVerifiedComplaint() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        String reviewId = publishReview(actor, uscc, CLEAN_CONTENT);
        String complaintId = appealOk(uscc, reviewId);
        verify(adminToken("CONTENT_ADMIN"), complaintId);
        return new ComplaintContext(uscc, reviewId, complaintId, actor);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void setDeadline(String complaintId, String interval) {
        jdbcTemplate.update("UPDATE complaint SET deadline = now() " + interval + " WHERE id = ?",
                UUID.fromString(complaintId));
    }

    private JsonNode notifications(String token, String query) throws Exception {
        return body(getWithToken("/api/users/me/notifications" + query, token)).path("data");
    }

    // ------------------------------------------------------------------
    // 争议开始：作者站内 + 短信
    // ------------------------------------------------------------------

    @Test
    void disputeStartedSendsBothChannelsToAuthor() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT channel, status, read_at FROM notification WHERE user_id = ? "
                        + "AND type = 'DISPUTE_STARTED' AND ref_id = ?",
                context.actor().userId(), context.complaintId());
        assertThat(row.get("channel")).isEqualTo("BOTH");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("read_at")).isNull();

        int total = count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'DISPUTE_STARTED' "
                + "AND ref_id = ?", context.actor().userId(), context.complaintId());
        assertThat(total).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AC-19：截止前 24h 提醒，站内 + 短信，双方未举证都发，已举证方不发，重复调度不重复
    // ------------------------------------------------------------------

    @Test
    void evidenceReminderIsIdempotentAndSkipsSubmittedSide() throws Exception {
        ComplaintContext bothMissing = prepareVerifiedComplaint();
        setDeadline(bothMissing.complaintId(), "+ interval '20 hours'");

        assertThat(complaintDeadlineJob.sendEvidenceReminders()).isGreaterThanOrEqualTo(1);
        int userNotified = count("SELECT count(*) FROM notification WHERE user_id = ? "
                        + "AND type = 'EVIDENCE_REMINDER' AND ref_id = ? AND channel = 'BOTH' AND status = 'SENT'",
                bothMissing.actor().userId(), bothMissing.complaintId());
        assertThat(userNotified).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                        + "AND action = 'EVIDENCE_REMINDER_ENTERPRISE'",
                UUID.fromString(bothMissing.reviewId()))).isEqualTo(1);

        // 重复调度：不重复发站内通知，也不重复发企业联系人短信。
        complaintDeadlineJob.sendEvidenceReminders();
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? "
                        + "AND type = 'EVIDENCE_REMINDER' AND ref_id = ?",
                bothMissing.actor().userId(), bothMissing.complaintId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                        + "AND action = 'EVIDENCE_REMINDER_ENTERPRISE'",
                UUID.fromString(bothMissing.reviewId()))).isEqualTo(1);

        // 作者已举证：只提醒企业，不再打扰作者。
        ComplaintContext userSubmitted = prepareVerifiedComplaint();
        mockMvc.perform(multipart("/api/complaints/" + userSubmitted.complaintId() + "/evidence")
                        .file(material("user-evidence.pdf"))
                        .param("grade", "B")
                        .param("note", "工资条与聊天记录")
                        .header("Authorization", "Bearer " + userSubmitted.actor().token()))
                .andExpect(status().isOk());
        setDeadline(userSubmitted.complaintId(), "+ interval '20 hours'");
        complaintDeadlineJob.sendEvidenceReminders();
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? "
                        + "AND type = 'EVIDENCE_REMINDER' AND ref_id = ?",
                userSubmitted.actor().userId(), userSubmitted.complaintId())).isZero();
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                        + "AND action = 'EVIDENCE_REMINDER_ENTERPRISE'",
                UUID.fromString(userSubmitted.reviewId()))).isEqualTo(1);
    }

    @Test
    void evidenceReminderIsNotSentBefore24HourWindow() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        setDeadline(context.complaintId(), "+ interval '40 hours'");
        complaintDeadlineJob.sendEvidenceReminders();
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? "
                        + "AND type = 'EVIDENCE_REMINDER' AND ref_id = ?",
                context.actor().userId(), context.complaintId())).isZero();
    }

    // ------------------------------------------------------------------
    // 短信失败重试 3 次并记录 last_error
    // ------------------------------------------------------------------

    @Test
    void smsFailureRetriesThreeTimesAndRecordsError() throws Exception {
        Actor actor = registerActor();
        providersProperties.getMock().getSms().setFailFirstAttempts(3);
        mockSms().resetNotificationFailureCounter();

        UUID refId = UUID.randomUUID();
        Notification notification = notificationService.send(actor.userId(),
                NotificationType.EVIDENCE_REMINDER, "举证截止提醒", "短信失败重试测试",
                NotificationChannel.BOTH, "COMPLAINT", refId.toString());
        assertThat(notification.getStatus()).isEqualTo("FAILED");
        assertThat(notification.getRetryCount()).isEqualTo(1);
        assertThat(notification.getLastError()).contains("injected failure");

        notificationRetryJob.retryFailed();
        notificationRetryJob.retryFailed();
        Notification stored = notificationRepositoryRow(notification.getId());
        assertThat(stored.getStatus()).isEqualTo("FAILED");
        assertThat(stored.getRetryCount()).isEqualTo(3);
        assertThat(stored.getLastError()).contains("injected failure");

        // 达到 3 次后不再重试。
        assertThat(notificationRetryJob.retryFailed()).isZero();
        assertThat(notificationRepositoryRow(notification.getId()).getRetryCount()).isEqualTo(3);
    }

    private Notification notificationRepositoryRow(UUID id) {
        return notificationRepository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------
    // AC-11：截止后双方无证据 → 自动 FOLDED + 通知
    // ------------------------------------------------------------------

    @Test
    void deadlineWithNoEvidenceAutoFoldsAndNotifiesOnce() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        setDeadline(context.complaintId(), "- interval '1 hour'");

        assertThat(complaintDeadlineJob.processDeadlines()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM complaint WHERE id = ?",
                String.class, UUID.fromString(context.complaintId()))).isEqualTo("RULED_INSUFFICIENT");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(context.reviewId()))).isEqualTo("FOLDED");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM company WHERE uscc = ?",
                Integer.class, context.uscc())).isZero();

        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                        + "AND action = 'TIMEOUT_RULING' AND actor_type = 'SYSTEM'",
                UUID.fromString(context.reviewId()))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'TIMEOUT_RULING' "
                + "AND target_id = ?", context.complaintId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'RULING_RESULT' "
                        + "AND ref_id = ? AND channel = 'BOTH'",
                context.actor().userId(), context.complaintId())).isEqualTo(1);

        JsonNode detail = body(mockMvc.perform(get("/api/reviews/" + context.reviewId()))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(detail.path("collapsed").asBoolean()).isTrue();
        assertThat(detail.path("collapseNote").asText()).contains("争议未决");

        // 幂等：再次调度不重复改状态/发通知。
        complaintDeadlineJob.processDeadlines();
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'RULING_RESULT' "
                        + "AND ref_id = ?", context.actor().userId(), context.complaintId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                        + "AND action = 'TIMEOUT_RULING'",
                UUID.fromString(context.reviewId()))).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 截止后仅一方有证据 → PENDING_RULING，不改点评状态
    // ------------------------------------------------------------------

    @Test
    void deadlineWithOneSideEvidenceMovesToPendingRulingWithoutChangingReview() throws Exception {
        ComplaintContext context = prepareVerifiedComplaint();
        mockMvc.perform(multipart("/api/complaints/" + context.complaintId() + "/evidence")
                        .file(material("user-evidence.pdf"))
                        .param("grade", "B")
                        .param("note", "工资条")
                        .header("Authorization", "Bearer " + context.actor().token()))
                .andExpect(status().isOk());
        setDeadline(context.complaintId(), "- interval '1 hour'");

        assertThat(complaintDeadlineJob.processDeadlines()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM complaint WHERE id = ?",
                String.class, UUID.fromString(context.complaintId()))).isEqualTo("PENDING_RULING");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, UUID.fromString(context.reviewId()))).isEqualTo("DISPUTED");
        assertThat(count("SELECT count(*) FROM review_audit WHERE review_id = ? "
                        + "AND action = 'TIMEOUT_RULING'", UUID.fromString(context.reviewId()))).isZero();

        // 重复调度保持 PENDING_RULING。
        complaintDeadlineJob.processDeadlines();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM complaint WHERE id = ?",
                String.class, UUID.fromString(context.complaintId()))).isEqualTo("PENDING_RULING");
    }

    // ------------------------------------------------------------------
    // 佐证驳回通知
    // ------------------------------------------------------------------

    @Test
    void verificationRejectedSendsNotification() throws Exception {
        Actor actor = registerActor();
        mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", VALID_ID))))
                .andExpect(status().isOk());
        String companyUscc = "91" + String.format("%016d", USCC_SEQ.incrementAndGet());
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                + "VALUES (?, ?, '杭州', '互联网', 'MANUAL', 'ACTIVE')",
                companyUscc, "佐证测试公司" + companyUscc.substring(10));
        UUID verificationId = jdbcTemplate.queryForObject("""
                INSERT INTO user_verification
                    (user_id, proof_type, company_uscc, period_start, period_end, status)
                VALUES (?, 'LABOR_CONTRACT', ?, current_date, current_date, 'PENDING')
                RETURNING id
                """, UUID.class, actor.userId(), companyUscc);

        MvcResult rejected = mockMvc.perform(post("/api/admin/verifications/" + verificationId + "/reject")
                        .header("Authorization", "Bearer " + adminToken("CONTENT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reasonCode", "MATERIAL_BLURRY", "note", "图片不清晰"))))
                .andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? "
                        + "AND type = 'VERIFICATION_REJECTED' AND ref_id = ? AND channel = 'BOTH'",
                actor.userId(), verificationId.toString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 申诉结果通知
    // ------------------------------------------------------------------

    @Test
    void appealResultSendsNotificationAfterManualReview() throws Exception {
        String uscc = createCompany();
        Actor actor = verifiedActor(uscc);
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uscc", uscc);
        body.put("periodStart", now.minusMonths(6).toString());
        body.put("periodEnd", now.plusMonths(1).toString());
        body.put("totalScore", 3);
        body.put("content", SPECIFIC_CLAIM_CONTENT);
        body.put("dimensions", dimensions());
        MvcResult submitted = mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + actor.token())
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(submitted.getResponse().getStatus()).isEqualTo(201);
        String reviewId = body(submitted).path("data").path("id").asText();
        assertThat(body(submitted).path("data").path("status").asText()).isEqualTo("PENDING_MANUAL");

        String admin = adminToken("CONTENT_ADMIN");
        assertThat(mockMvc.perform(post("/api/admin/reviews/" + reviewId + "/reject")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reasonCode", "INSUFFICIENT_EVIDENCE", "note", "缺少佐证"))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'APPEAL_RESULT' "
                + "AND ref_id = ?", actor.userId(), reviewId)).isZero();

        assertThat(mockMvc.perform(post("/api/reviews/" + reviewId + "/appeal")
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("statement", "已补充说明"))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(mockMvc.perform(post("/api/admin/reviews/" + reviewId + "/approve")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reasonCode", "CONTENT_COMPLIANT", "note", "申诉通过"))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'APPEAL_RESULT' "
                + "AND ref_id = ? AND channel = 'BOTH' AND status = 'SENT'",
                actor.userId(), reviewId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 通知列表 / 已读 / 全部已读 / 越权 403
    // ------------------------------------------------------------------

    @Test
    void notificationListAndReadApisEnforceOwnership() throws Exception {
        Actor actor = registerActor();
        Notification first = notificationService.send(actor.userId(), NotificationType.DISPUTE_STARTED,
                "争议开始", "第一条通知", NotificationChannel.INAPP, "COMPLAINT",
                UUID.randomUUID().toString());
        Thread.sleep(20);
        Notification second = notificationService.send(actor.userId(), NotificationType.RULING_RESULT,
                "裁定结果", "第二条通知", NotificationChannel.INAPP, "COMPLAINT",
                UUID.randomUUID().toString());

        JsonNode page = notifications(actor.token(), "?page=1&size=20");
        assertThat(page.path("total").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("unreadCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(second.getId().toString());
        assertThat(page.path("items").get(0).path("type").asText()).isEqualTo("RULING_RESULT");
        assertThat(page.path("items").get(1).path("id").asText()).isEqualTo(first.getId().toString());

        mockMvc.perform(post("/api/users/me/notifications/" + second.getId() + "/read")
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT read_at IS NOT NULL FROM notification WHERE id = ?",
                Boolean.class, second.getId())).isTrue();

        JsonNode unread = notifications(actor.token(), "?unreadOnly=true&page=1&size=20");
        assertThat(unread.path("unreadCount").asInt()).isLessThan(page.path("unreadCount").asInt());
        for (JsonNode item : unread.path("items")) {
            assertThat(item.path("id").asText()).isNotEqualTo(second.getId().toString());
        }

        mockMvc.perform(post("/api/users/me/notifications/read-all")
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk());
        assertThat(notifications(actor.token(), "?page=1&size=20").path("unreadCount").asInt()).isZero();

        // 越权读他人通知 → 403。
        Actor other = registerActor();
        mockMvc.perform(post("/api/users/me/notifications/" + first.getId() + "/read")
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isForbidden());
    }
}
