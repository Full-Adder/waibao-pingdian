package com.waibao.pingdian.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.account.service.ContributionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityContributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ContributionService contributionService;

    private JsonNode newUser() throws Exception {
        return registerAndLogin(uniquePhone(), uniqueIp());
    }

    @Test
    void insufficientContributionReturns403WithDifference() throws Exception {
        JsonNode user = newUser();
        String token = user.path("token").asText();

        JsonNode identity = body(getWithToken("/api/users/me/identity", token)).path("data");
        assertThat(identity.path("alias").asText()).startsWith("匿名·");
        assertThat(identity.path("insufficientPoints").asInt()).isEqualTo(60);
        assertThat(identity.path("canChange").asBoolean()).isFalse();

        MvcResult change = mockMvc.perform(post("/api/users/me/identity/change")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(change).path("message").asText()).contains("贡献值不足").contains("60");
    }

    @Test
    void awardIsIdempotentAndSumsCorrectly() throws Exception {
        JsonNode user = newUser();
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        UUID verificationId = UUID.randomUUID();

        assertThat(contributionService.award(userId, com.waibao.pingdian.account.domain.ContributionReason.VERIFICATION,
                "VERIFICATION", verificationId.toString(), 50)).isTrue();
        assertThat(contributionService.award(userId, com.waibao.pingdian.account.domain.ContributionReason.VERIFICATION,
                "VERIFICATION", verificationId.toString(), 50)).isFalse();
        assertThat(score(userId)).isEqualTo(50);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contribution_ledger WHERE user_id = ? AND reason = 'VERIFICATION'",
                Integer.class, userId)).isEqualTo(1);

        contributionService.awardValidReport(userId, UUID.randomUUID());
        assertThat(score(userId)).isEqualTo(70);
    }

    @Test
    void changeIdentityRetiresOldAndEnforcesCooldown() throws Exception {
        JsonNode user = newUser();
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        String token = user.path("token").asText();
        String oldAlias = body(getWithToken("/api/users/me/identity", token)).path("data").path("alias").asText();

        contributionService.awardVerificationApproved(userId, UUID.randomUUID());
        contributionService.awardValidReport(userId, UUID.randomUUID());
        assertThat(score(userId)).isEqualTo(70);

        MvcResult changed = mockMvc.perform(post("/api/users/me/identity/change")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = body(changed).path("data");
        String newAlias = data.path("identity").path("alias").asText();
        assertThat(newAlias).startsWith("匿名·").isNotEqualTo(oldAlias);
        assertThat(data.path("remainingChangesThisYear").asLong()).isEqualTo(1);
        assertThat(data.path("identity").path("insufficientPoints").asInt()).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM anonymous_identity WHERE alias = ?", String.class, oldAlias))
                .isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM anonymous_identity WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId)).isEqualTo(1);
        assertThat(score(userId)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contribution_ledger WHERE user_id = ? AND reason = 'IDENTITY_CHANGE'",
                Integer.class, userId)).isEqualTo(1);

        // 补充贡献值后再换，命中 90 天冷却。
        for (int i = 0; i < 3; i++) {
            contributionService.awardValidReport(userId, UUID.randomUUID());
        }
        MvcResult second = mockMvc.perform(post("/api/users/me/identity/change")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(second).path("message").asText()).contains("天");
    }

    @Test
    void thirdChangeInSameYearRejected() throws Exception {
        JsonNode user = newUser();
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        String token = user.path("token").asText();

        jdbcTemplate.update("UPDATE user_account SET contribution_score = 100 WHERE id = ?", userId);
        jdbcTemplate.update("INSERT INTO contribution_ledger (user_id, delta, reason, ref_id) "
                + "VALUES (?, -60, 'IDENTITY_CHANGE', 'seed-1')", userId);
        jdbcTemplate.update("INSERT INTO contribution_ledger (user_id, delta, reason, ref_id) "
                + "VALUES (?, -60, 'IDENTITY_CHANGE', 'seed-2')", userId);

        MvcResult third = mockMvc.perform(post("/api/users/me/identity/change")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(third.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(third).path("message").asText()).contains("次数");
    }

    private int score(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class, userId);
    }
}
