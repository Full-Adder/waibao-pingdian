package com.waibao.pingdian.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.review.service.ReviewAggregateCalibrationJob;
import com.waibao.pingdian.review.service.ReviewDraftCleanupJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(1000);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CLEAN_CONTENT =
            "公司整体氛围不错，项目安排合理，工资发放也比较准时，沟通顺畅，适合积累经验。";
    private static final String CLEAN_CONTENT_2 =
            "团队协作比较顺畅，需求变更会提前沟通，结算周期稳定，整体体验符合预期，可以推荐。";

    @Autowired
    private ReviewAggregateCalibrationJob calibrationJob;

    @Autowired
    private ReviewDraftCleanupJob draftCleanupJob;

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
                + "VALUES (?, ?, '杭州', '互联网', 'MANUAL', 'ACTIVE')", uscc, "测试公司" + uscc.substring(10));
        return uscc;
    }

    private Actor verifiedActor(String uscc, String start, String end) throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        jdbcTemplate.update("""
                        INSERT INTO user_verification
                            (user_id, proof_type, company_uscc, period_start, period_end, status)
                        VALUES (?, 'LABOR_CONTRACT', ?, ?, ?, 'APPROVED')
                        """,
                userId, uscc, LocalDate.parse(start + "-01"), LocalDate.parse(end + "-01"));
        return new Actor(login.path("token").asText(), userId);
    }

    private Actor currentPeriodActor(String uscc) throws Exception {
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        return verifiedActor(uscc, now.minusMonths(6).toString(), now.plusMonths(1).toString());
    }

    private Map<String, Object> reviewBody(String uscc, String start, String end, int score,
                                           List<Map<String, Object>> dimensions, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uscc", uscc);
        body.put("periodStart", start);
        body.put("periodEnd", end);
        body.put("totalScore", score);
        body.put("content", content);
        body.put("dimensions", dimensions);
        return body;
    }

    private List<Map<String, Object>> dimensions(int count) {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] codes = {"SALARY", "CONTRACT", "MANAGEMENT", "PROJECT_QUALITY", "WORK_REALITY", "RECOMMEND"};
        for (int i = 0; i < count; i++) {
            list.add(Map.of("code", codes[i], "score", 4));
        }
        return list;
    }

    private MvcResult submitRaw(Actor actor, Map<String, Object> body) throws Exception {
        String ip = uniqueIp();
        return mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + actor.token())
                        .header("X-Forwarded-For", ip)
                        .header("X-Device-Id", "device-" + ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode submitOk(Actor actor, String uscc, String start, String end, int score, String content)
            throws Exception {
        MvcResult result = submitRaw(actor, reviewBody(uscc, start, end, score, dimensions(3), content));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(result);
        assertSuccess(body);
        return body.path("data");
    }

    private String[] uniquePeriod(int index) {
        YearMonth now = YearMonth.now(BUSINESS_ZONE);
        return new String[]{
                now.minusMonths(9 + index * 3).toString(),
                now.minusMonths(6 + index * 3).toString()
        };
    }

    private JsonNode companyRow(String uscc) {
        return objectMapper.valueToTree(jdbcTemplate.queryForMap(
                "SELECT review_count, avg_score, score_visible FROM company WHERE uscc = ?", uscc));
    }

    private JsonNode aiFlagsOf(String reviewId) throws Exception {
        Object flags = jdbcTemplate.queryForObject(
                "SELECT ai_flags FROM review WHERE id = ?", Object.class, UUID.fromString(reviewId));
        return objectMapper.readTree(flags.toString());
    }

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    // AC-01：游客直接调发评接口 → 403，数据库无点评记录
    @Test
    void guestCannotSubmitReviewAndNoRecordIsCreated() throws Exception {
        String uscc = createCompany();

        MvcResult result = mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewBody(uscc, "2025-01", "2025-06", 4,
                                dimensions(3), CLEAN_CONTENT)))
                        .header("X-Forwarded-For", uniqueIp()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result).path("message").asText()).contains("请先登录");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review WHERE uscc = ?", Integer.class, uscc)).isZero();
    }

    // AC-02：已实名但未提交在职佐证/核验未通过 → 403 并给出佐证入口
    @Test
    void realnameVerifiedUserWithoutVerificationIsForbidden() throws Exception {
        String uscc = createCompany();
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        String token = login.path("token").asText();
        UUID userId = UUID.fromString(login.path("user").path("id").asText());

        MvcResult realname = mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "张三", "idNumber", "110101199001010015"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(realname).path("data").path("success").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT realname_status FROM user_account WHERE id = ?", String.class, userId))
                .isEqualTo("VERIFIED");

        MvcResult result = submitRaw(new Actor(token, userId),
                reviewBody(uscc, "2025-01", "2025-06", 4, dimensions(3), CLEAN_CONTENT));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result).path("message").asText()).contains("佐证");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    // AC-04：2 条已发布评价时不展示综合分，但两条正文均可读
    @Test
    void twoPublishedReviewsShowScoreGateAndBothContents() throws Exception {
        String uscc = createCompany();
        String[] period = uniquePeriod(0);

        submitOk(currentPeriodActor(uscc), uscc, period[0], period[1], 4, CLEAN_CONTENT);
        submitOk(currentPeriodActor(uscc), uscc, period[0], period[1], 2, CLEAN_CONTENT_2);

        JsonNode detail = body(mockMvc.perform(get("/api/companies/{uscc}", uscc))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(detail.path("reviewCount").asInt()).isEqualTo(2);
        assertThat(detail.path("scoreVisible").asBoolean()).isFalse();
        assertThat(detail.path("scoreNote").asText()).isEqualTo("评价不足，暂不评分");

        JsonNode reviews = body(mockMvc.perform(get("/api/companies/{uscc}/reviews", uscc))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(reviews.path("total").asInt()).isEqualTo(2);
        List<String> contents = reviews.path("items").findValuesAsText("content");
        assertThat(contents).containsExactlyInAnyOrder(CLEAN_CONTENT, CLEAN_CONTENT_2);
    }

    @Test
    void unverifiedUserCannotSubmitReview() throws Exception {
        String uscc = createCompany();
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        Actor actor = new Actor(login.path("token").asText(),
                UUID.fromString(login.path("user").path("id").asText()));

        MvcResult result = submitRaw(actor,
                reviewBody(uscc, "2025-01", "2025-06", 4, dimensions(3), CLEAN_CONTENT));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result).path("message").asText()).contains("佐证");
    }

    @Test
    void fewerThanThreeDimensionsRejectedWithFieldError() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);

        MvcResult result = submitRaw(actor,
                reviewBody(uscc, period[0], period[1], 4, dimensions(2), CLEAN_CONTENT));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode response = body(result);
        assertThat(response.path("data").path("errors").toString()).contains("dimensions")
                .contains("至少选择 3 个维度");
    }

    @Test
    void duplicateSamePeriodRejectedWithOriginalReviewId() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);

        JsonNode first = submitOk(actor, uscc, period[0], period[1], 4, CLEAN_CONTENT);

        MvcResult second = submitRaw(actor,
                reviewBody(uscc, period[0], period[1], 4, dimensions(3), CLEAN_CONTENT_2));

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        JsonNode response = body(second);
        assertThat(response.path("data").path("reviewId").asText()).isEqualTo(first.path("id").asText());
        assertThat(response.path("message").asText()).contains("编辑原评价");
    }

    @Test
    void editingAfterNineDaysRejected() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);
        JsonNode created = submitOk(actor, uscc, period[0], period[1], 4, CLEAN_CONTENT);
        UUID reviewId = UUID.fromString(created.path("id").asText());

        jdbcTemplate.update("UPDATE review SET published_at = now() - interval '9 days' WHERE id = ?", reviewId);

        Map<String, Object> patchBody = new LinkedHashMap<>();
        patchBody.put("totalScore", 5);
        patchBody.put("content", CLEAN_CONTENT_2);
        patchBody.put("dimensions", dimensions(3));

        MvcResult result = mockMvc.perform(patch("/api/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchBody)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result).path("message").asText()).contains("7 天编辑窗口");
    }

    @Test
    void editingMovesToEditingThenRestoresPublishedWithoutDoubleContribution() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);
        JsonNode created = submitOk(actor, uscc, period[0], period[1], 4, CLEAN_CONTENT);
        UUID reviewId = UUID.fromString(created.path("id").asText());
        assertThat(created.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class, actor.userId()))
                .isEqualTo(20);

        Map<String, Object> patchBody = new LinkedHashMap<>();
        patchBody.put("totalScore", 5);
        patchBody.put("content", CLEAN_CONTENT_2);
        patchBody.put("dimensions", dimensions(3));

        MvcResult result = mockMvc.perform(patch("/api/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + actor.token())
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchBody)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode updated = body(result).path("data");
        assertThat(updated.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(updated.path("editCount").asInt()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, edit_count, published_at FROM review WHERE id = ?", reviewId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) row.get("edit_count")).intValue()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'EDIT'",
                Integer.class, reviewId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'RESUBMIT_EDIT'",
                Integer.class, reviewId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_audit WHERE review_id = ? AND action = 'AI_RE_REVIEW_PUBLISH'",
                Integer.class, reviewId)).isEqualTo(1);

        JsonNode company = companyRow(uscc);
        assertThat(company.path("review_count").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class, actor.userId()))
                .isEqualTo(20);
    }

    @Test
    void withdrawRemovesFromScoreAndIndexAndBlocksThirtyDays() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);
        JsonNode created = submitOk(actor, uscc, period[0], period[1], 4, CLEAN_CONTENT);
        UUID reviewId = UUID.fromString(created.path("id").asText());
        assertThat(companyRow(uscc).path("review_count").asInt()).isEqualTo(1);

        mockMvc.perform(post("/api/reviews/" + reviewId + "/withdraw")
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, withdrawn_at FROM review WHERE id = ?", reviewId);
        assertThat(row.get("status")).isEqualTo("WITHDRAWN");
        assertThat(row.get("withdrawn_at")).isNotNull();
        assertThat(companyRow(uscc).path("review_count").asInt()).isZero();
        assertThat(companyRow(uscc).path("score_visible").asBoolean()).isFalse();

        JsonNode list = body(mockMvc.perform(get("/api/companies/" + uscc + "/reviews"))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(list.path("total").asInt()).isZero();

        // 30 天内同公司再发 → 403
        MvcResult banned = submitRaw(actor,
                reviewBody(uscc, "2024-01", "2024-06", 4, dimensions(3), CLEAN_CONTENT_2));
        assertThat(banned.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(banned).path("data").path("remainingDays").asInt()).isGreaterThanOrEqualTo(29);

        // 撤回超过 30 天后释放该时间段名额
        jdbcTemplate.update("UPDATE review SET withdrawn_at = now() - interval '40 days' WHERE id = ?", reviewId);
        JsonNode again = submitOk(actor, uscc, period[0], period[1], 5, CLEAN_CONTENT_2);
        assertThat(again.path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void ninetyDayIntervalEnforcedAndReleasedAfterWindow() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] firstPeriod = uniquePeriod(0);
        JsonNode first = submitOk(actor, uscc, firstPeriod[0], firstPeriod[1], 4, CLEAN_CONTENT);
        UUID firstId = UUID.fromString(first.path("id").asText());

        jdbcTemplate.update("UPDATE review SET published_at = now() - interval '30 days' WHERE id = ?", firstId);

        String[] secondPeriod = uniquePeriod(1);
        MvcResult blocked = submitRaw(actor,
                reviewBody(uscc, secondPeriod[0], secondPeriod[1], 4, dimensions(3), CLEAN_CONTENT_2));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        int remainingDays = body(blocked).path("data").path("remainingDays").asInt();
        assertThat(remainingDays).isBetween(55, 60);

        jdbcTemplate.update("UPDATE review SET published_at = now() - interval '100 days' WHERE id = ?", firstId);
        JsonNode second = submitOk(actor, uscc, secondPeriod[0], secondPeriod[1], 5, CLEAN_CONTENT_2);
        assertThat(second.path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void thirdPublishedReviewRecomputesAggregateAndCompanyCapEnforced() throws Exception {
        String uscc = createCompany();
        String[] p1 = uniquePeriod(0);
        String[] p2 = uniquePeriod(1);
        String[] p3 = uniquePeriod(2);
        String[] p4 = uniquePeriod(3);

        submitOk(currentPeriodActor(uscc), uscc, p1[0], p1[1], 4, CLEAN_CONTENT);
        submitOk(currentPeriodActor(uscc), uscc, p2[0], p2[1], 3, CLEAN_CONTENT_2);

        JsonNode two = companyRow(uscc);
        assertThat(two.path("review_count").asInt()).isEqualTo(2);
        assertThat(two.path("score_visible").asBoolean()).isFalse();

        submitOk(currentPeriodActor(uscc), uscc, p3[0], p3[1], 5,
                "结算及时，负责人沟通清楚，项目节奏稳定，整体符合岗位描述，体验不错。");

        JsonNode three = companyRow(uscc);
        assertThat(three.path("review_count").asInt()).isEqualTo(3);
        assertThat(three.path("score_visible").asBoolean()).isTrue();
        assertThat(three.path("avg_score").decimalValue()).isEqualByComparingTo("4.0");

        calibrationJob.calibrateAll();
        assertThat(companyRow(uscc).path("review_count").asInt()).isEqualTo(3);
        assertThat(companyRow(uscc).path("avg_score").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(companyRow(uscc).path("score_visible").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM risk_signal WHERE signal_type = 'AGGREGATE_DRIFT' AND subject_value = ?",
                Integer.class, uscc)).isZero();
    }

    @Test
    void companyCapIsThreePerUser() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] p1 = uniquePeriod(0);
        String[] p2 = uniquePeriod(1);
        String[] p3 = uniquePeriod(2);
        String[] p4 = uniquePeriod(3);

        JsonNode first = submitOk(actor, uscc, p1[0], p1[1], 4, CLEAN_CONTENT);
        jdbcTemplate.update("UPDATE review SET published_at = now() - interval '200 days' WHERE id = ?",
                UUID.fromString(first.path("id").asText()));

        JsonNode second = submitOk(actor, uscc, p2[0], p2[1], 4, CLEAN_CONTENT_2);
        jdbcTemplate.update("UPDATE review SET published_at = now() - interval '200 days' WHERE id = ?",
                UUID.fromString(second.path("id").asText()));

        submitOk(actor, uscc, p3[0], p3[1], 5,
                "结算及时，负责人沟通清楚，项目节奏稳定，整体符合岗位描述，体验不错。");

        MvcResult fourth = submitRaw(actor,
                reviewBody(uscc, p4[0], p4[1], 4, dimensions(3),
                        "团队氛围友好，需求评审充分，结算按合同执行，整体符合预期，值得推荐。"));
        assertThat(fourth.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(fourth).path("message").asText()).contains("3 条");
    }

    @Test
    void copyDetectedAcrossThreeCompaniesForcesManualReview() throws Exception {
        String uscc1 = createCompany();
        String uscc2 = createCompany();
        String uscc3 = createCompany();
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        Actor actor = new Actor(login.path("token").asText(), userId);
        String[] period = uniquePeriod(0);
        for (String uscc : List.of(uscc1, uscc2, uscc3)) {
            jdbcTemplate.update("""
                            INSERT INTO user_verification
                                (user_id, proof_type, company_uscc, period_start, period_end, status)
                            VALUES (?, 'LABOR_CONTRACT', ?, ?, ?, 'APPROVED')
                            """,
                    userId, uscc, LocalDate.parse(period[0] + "-01"), LocalDate.parse(period[1] + "-01"));
        }

        JsonNode first = submitOk(actor, uscc1, period[0], period[1], 4, CLEAN_CONTENT);
        assertThat(first.path("status").asText()).isEqualTo("PUBLISHED");
        JsonNode second = submitOk(actor, uscc2, period[0], period[1], 4, CLEAN_CONTENT);
        assertThat(second.path("status").asText()).isEqualTo("PUBLISHED");

        JsonNode third = submitOk(actor, uscc3, period[0], period[1], 4, CLEAN_CONTENT);
        assertThat(third.path("status").asText()).isEqualTo("PENDING_MANUAL");
        JsonNode storedFlags = aiFlagsOf(third.path("id").asText());
        assertThat(storedFlags.path("copyDetected").asBoolean()).isTrue();
    }

    @Test
    void privacyRedlineRejectedWithoutAppealAndSpecificAllegationGoesToManual() throws Exception {
        String privacyUscc = createCompany();
        Actor privacyActor = currentPeriodActor(privacyUscc);
        String[] period = uniquePeriod(0);

        JsonNode rejected = submitOk(privacyActor, privacyUscc, period[0], period[1], 4,
                "该公司管理混乱，拖欠工资，联系人手机号13800138000，希望大家谨慎选择。");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(aiFlagsOf(rejected.path("id").asText()).path("noAppeal").asBoolean()).isTrue();
        assertThat(rejected.path("rejectReasonCode").asText()).isEqualTo("PRIVACY");

        JsonNode mine = body(getWithToken("/api/reviews/mine", privacyActor.token())).path("data");
        JsonNode item = mine.path("items").get(0);
        assertThat(item.path("status").asText()).isEqualTo("REJECTED");
        assertThat(item.path("rejectReasonCode").asText()).isEqualTo("PRIVACY");
        assertThat(item.path("rejectMessage").asText()).contains("隐私");
        assertThat(item.path("canAppeal").asBoolean()).isFalse();

        String allegationUscc = createCompany();
        Actor allegationActor = currentPeriodActor(allegationUscc);
        JsonNode manual = submitOk(allegationActor, allegationUscc, period[0], period[1], 3,
                "公司在项目期间拖欠工资 3 个月，一直没有发放，也没有任何说明，希望尽快处理。");
        assertThat(manual.path("status").asText()).isEqualTo("PENDING_MANUAL");
        assertThat(aiFlagsOf(manual.path("id").asText()).path("specificAllegation").asBoolean()).isTrue();
    }

    @Test
    void identityChangeKeepsHistoricalReviewAlias() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);

        jdbcTemplate.update("UPDATE user_account SET contribution_score = 100 WHERE id = ?", actor.userId());
        JsonNode created = submitOk(actor, uscc, period[0], period[1], 4, CLEAN_CONTENT);
        String oldAlias = created.path("identityAlias").asText();
        assertThat(oldAlias).isNotBlank();

        mockMvc.perform(post("/api/users/me/identity/change")
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk());

        JsonNode mine = body(getWithToken("/api/reviews/mine", actor.token())).path("data");
        assertThat(mine.path("items").get(0).path("identityAlias").asText()).isEqualTo(oldAlias);

        JsonNode publicList = body(mockMvc.perform(get("/api/companies/" + uscc + "/reviews"))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(publicList.path("items").get(0).path("identityAlias").asText()).isEqualTo(oldAlias);
    }

    @Test
    void draftCanBeSubmittedAndStaleDraftCleaned() throws Exception {
        String uscc = createCompany();
        Actor actor = currentPeriodActor(uscc);
        String[] period = uniquePeriod(0);

        Map<String, Object> draftBody = reviewBody(uscc, period[0], period[1], 4, dimensions(3), CLEAN_CONTENT);
        MvcResult draft = mockMvc.perform(post("/api/reviews/drafts")
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode draftData = body(draft).path("data");
        assertThat(draftData.path("status").asText()).isEqualTo("DRAFT");
        UUID draftId = UUID.fromString(draftData.path("id").asText());

        MvcResult submitted = mockMvc.perform(post("/api/reviews/" + draftId + "/submit")
                        .header("Authorization", "Bearer " + actor.token())
                        .header("X-Forwarded-For", uniqueIp()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(submitted).path("data").path("status").asText()).isEqualTo("PUBLISHED");

        Map<String, Object> staleBody = reviewBody(uscc, period[0], period[1], 4, dimensions(3), CLEAN_CONTENT_2);
        staleBody.remove("uscc");
        staleBody.put("uscc", uscc);
        UUID staleId = UUID.fromString(body(mockMvc.perform(post("/api/reviews/drafts")
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(staleBody)))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asText());
        jdbcTemplate.update("UPDATE review SET updated_at = now() - interval '10 days' WHERE id = ?", staleId);

        assertThat(draftCleanupJob.cleanupDrafts()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review WHERE id = ?",
                Integer.class, staleId)).isZero();
    }
}
