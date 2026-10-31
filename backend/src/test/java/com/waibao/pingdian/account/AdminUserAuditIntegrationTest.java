package com.waibao.pingdian.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 15 支撑：管理端用户列表、封禁、贡献值调整与审计查询（R17/R6/R19）。
 */
class AdminUserAuditIntegrationTest extends AbstractIntegrationTest {

    private record Actor(String token, UUID userId, String phone, String alias) {
    }

    private Actor registerActor() throws Exception {
        String phone = uniquePhone();
        JsonNode login = registerAndLogin(phone, uniqueIp());
        return new Actor(
                login.path("token").asText(),
                UUID.fromString(login.path("user").path("id").asText()),
                phone,
                login.path("user").path("identity").path("alias").asText());
    }

    private JsonNode listUsers(String token, String keyword, String status) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/users")
                        .param("keyword", keyword)
                        .param("status", status)
                        .param("page", "1")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result).path("data");
    }

    private JsonNode findUser(JsonNode page, UUID userId) {
        for (JsonNode item : page.path("items")) {
            if (userId.toString().equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    void userListSearchesByPhoneAndAliasWithMaskedPhone() throws Exception {
        Actor actor = registerActor();
        String admin = adminToken("CONTENT_ADMIN");

        JsonNode byPhone = findUser(listUsers(admin, actor.phone(), "ACTIVE"), actor.userId());
        assertThat(byPhone).isNotNull();
        assertThat(byPhone.path("phoneMasked").asText()).isEqualTo(maskedPhone(actor.phone()));
        assertThat(byPhone.path("phoneMasked").asText()).doesNotContain(actor.phone());
        assertThat(byPhone.path("alias").asText()).isEqualTo(actor.alias());
        assertThat(byPhone.path("realnameStatus").asText()).isEqualTo("UNVERIFIED");

        JsonNode byAlias = findUser(listUsers(admin, actor.alias(), "ACTIVE"), actor.userId());
        assertThat(byAlias).isNotNull();
        assertThat(byAlias.path("id").asText()).isEqualTo(actor.userId().toString());

        MvcResult detail = mockMvc.perform(get("/api/admin/users/" + actor.userId())
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(detail).path("data");
        assertThat(data.path("id").asText()).isEqualTo(actor.userId().toString());
        assertThat(data.path("alias").asText()).isEqualTo(actor.alias());
        assertThat(data.path("phoneMasked").asText()).isEqualTo(maskedPhone(actor.phone()));
        assertThat(data.path("verifications").isArray()).isTrue();
        assertThat(data.path("reviewStatusCounts").isObject()).isTrue();
    }

    @Test
    void contentAdminCannotBanButSuperAdminCanAndAuditIsWritten() throws Exception {
        Actor actor = registerActor();
        String contentAdmin = adminToken("CONTENT_ADMIN");

        MvcResult denied = mockMvc.perform(post("/api/admin/users/" + actor.userId() + "/ban")
                        .header("Authorization", "Bearer " + contentAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ban", true, "reason", "内容管理员无权封禁"))))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM user_account WHERE id = ?",
                String.class, actor.userId())).isEqualTo("ACTIVE");

        String superAdmin = adminToken("SUPER_ADMIN");
        MvcResult banned = mockMvc.perform(post("/api/admin/users/" + actor.userId() + "/ban")
                        .header("Authorization", "Bearer " + superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ban", true, "reason", "多次发布违规内容"))))
                .andReturn();
        assertThat(banned.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM user_account WHERE id = ?",
                String.class, actor.userId())).isEqualTo("BANNED");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'USER_BAN' "
                + "AND target_type = 'USER' AND target_id = ?", actor.userId().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT detail FROM admin_audit_log WHERE action = 'USER_BAN' AND target_id = ?",
                String.class, actor.userId().toString())).isEqualTo("多次发布违规内容");

        MvcResult unbanned = mockMvc.perform(post("/api/admin/users/" + actor.userId() + "/ban")
                        .header("Authorization", "Bearer " + superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ban", false, "reason", "申诉复核通过"))))
                .andReturn();
        assertThat(unbanned.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM user_account WHERE id = ?",
                String.class, actor.userId())).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'USER_UNBAN' "
                + "AND target_id = ?", actor.userId().toString())).isEqualTo(1);
    }

    @Test
    void contributionAdjustIsSuperAdminOnlyClampedAndIdempotentByRef() throws Exception {
        Actor actor = registerActor();
        String contentAdmin = adminToken("CONTENT_ADMIN");
        String superAdmin = adminToken("SUPER_ADMIN");

        MvcResult denied = postContribution(contentAdmin, actor.userId(), 50, "测试调整", null);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ?",
                actor.userId())).isZero();

        String refId = UUID.randomUUID().toString();
        MvcResult awarded = postContribution(superAdmin, actor.userId(), 50, "活动补偿", refId);
        assertThat(awarded.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class,
                actor.userId())).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                + "AND reason = 'ADMIN_ADJUST' AND ref_type = 'USER' AND ref_id = ?",
                actor.userId(), refId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delta FROM contribution_ledger WHERE user_id = ? AND ref_id = ?",
                Integer.class, actor.userId(), refId)).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTRIBUTION_ADJUST' "
                + "AND target_id = ?", actor.userId().toString())).isEqualTo(1);

        // 同一 refId 重复提交不重复入账。
        MvcResult repeated = postContribution(superAdmin, actor.userId(), 50, "活动补偿", refId);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                + "AND reason = 'ADMIN_ADJUST' AND ref_id = ?", actor.userId(), refId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class,
                actor.userId())).isEqualTo(50);

        // 扣减不得低于 0。
        MvcResult deducted = postContribution(superAdmin, actor.userId(), -1000, "违规扣减", null);
        assertThat(deducted.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class,
                actor.userId())).isZero();
        assertThat(count("SELECT count(*) FROM contribution_ledger WHERE user_id = ? "
                + "AND reason = 'ADMIN_ADJUST'", actor.userId())).isEqualTo(2);
    }

    @Test
    void auditLogsAreSuperAdminOnlyDescendingAndFilterable() throws Exception {
        Actor actor = registerActor();
        String superAdmin = adminToken("SUPER_ADMIN");
        mockMvc.perform(post("/api/admin/users/" + actor.userId() + "/ban")
                        .header("Authorization", "Bearer " + superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ban", true, "reason", "审计查询测试"))))
                .andReturn();

        String contentAdmin = adminToken("CONTENT_ADMIN");
        MvcResult denied = mockMvc.perform(get("/api/admin/audit-logs")
                        .param("targetId", actor.userId().toString())
                        .header("Authorization", "Bearer " + contentAdmin))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);

        MvcResult all = mockMvc.perform(get("/api/admin/audit-logs")
                        .param("targetId", actor.userId().toString())
                        .param("page", "1")
                        .param("size", "100")
                        .param("from", Instant.now().minusSeconds(600).toString())
                        .param("to", Instant.now().plusSeconds(600).toString())
                        .header("Authorization", "Bearer " + superAdmin))
                .andReturn();
        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = body(all).path("data").path("items");
        assertThat(items.size()).isGreaterThan(0);

        Instant previous = null;
        for (JsonNode item : items) {
            assertThat(item.path("targetId").asText()).isEqualTo(actor.userId().toString());
            assertThat(item.path("actorRole").asText()).isEqualTo("SUPER_ADMIN");
            Instant createdAt = Instant.parse(item.path("createdAt").asText());
            if (previous != null) {
                assertThat(createdAt).isBeforeOrEqualTo(previous);
            }
            previous = createdAt;
        }

        MvcResult filtered = mockMvc.perform(get("/api/admin/audit-logs")
                        .param("targetId", actor.userId().toString())
                        .param("action", "USER")
                        .param("targetType", "USER")
                        .param("page", "1")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + superAdmin))
                .andReturn();
        assertThat(filtered.getResponse().getStatus()).isEqualTo(200);
        JsonNode filteredItems = body(filtered).path("data").path("items");
        assertThat(filteredItems.size()).isGreaterThan(0);
        for (JsonNode item : filteredItems) {
            assertThat(item.path("action").asText()).startsWith("USER");
            assertThat(item.path("targetType").asText()).startsWith("USER");
        }
    }

    private MvcResult postContribution(String token, UUID userId, int delta,
                                       String reason, String refId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delta", delta);
        body.put("reason", reason);
        if (refId != null) {
            body.put("refId", refId);
        }
        return mockMvc.perform(post("/api/admin/users/" + userId + "/contribution")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private String maskedPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
