package com.waibao.pingdian.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 14 支撑：帮助与反馈工单提交、管理端查看与回复（R20/F28）。
 */
class FeedbackIntegrationTest extends AbstractIntegrationTest {

    private MvcResult submit(String category, String contact, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (category != null) {
            body.put("category", category);
        }
        if (contact != null) {
            body.put("contact", contact);
        }
        body.put("content", content);
        return mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode findTicket(JsonNode page, String ticketId) {
        for (JsonNode item : page.path("items")) {
            if (ticketId.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    @Test
    void submitListReplyAndAudit() throws Exception {
        String phone = uniquePhone();
        MvcResult created = submit("ACCOUNT", phone, "账号无法登录且验证码一直收不到，请帮忙核查。");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = body(created).path("data");
        String ticketId = data.path("ticketId").asText();
        assertThat(data.path("status").asText()).isEqualTo("PENDING");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT contact_cipher, content, status FROM feedback_ticket WHERE id = ?",
                UUID.fromString(ticketId));
        String cipher = (String) row.get("contact_cipher");
        assertThat(cipher).startsWith("gcm:");
        assertThat(cipher).doesNotContain(phone);
        assertThat(row.get("content")).isEqualTo("账号无法登录且验证码一直收不到，请帮忙核查。");
        assertThat(row.get("status")).isEqualTo("PENDING");

        String admin = adminToken("CONTENT_ADMIN");
        MvcResult list = mockMvc.perform(get("/api/admin/feedback")
                        .param("status", "PENDING")
                        .param("page", "1")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        JsonNode item = findTicket(body(list).path("data"), ticketId);
        assertThat(item).isNotNull();
        assertThat(item.path("contactMasked").asText()).isEqualTo(maskedPhone(phone));
        assertThat(item.path("contactMasked").asText()).doesNotContain(phone);
        assertThat(item.path("category").asText()).isEqualTo("ACCOUNT");

        MvcResult replied = mockMvc.perform(post("/api/admin/feedback/" + ticketId + "/reply")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "responseNote", "已收到您的反馈，我们会尽快核实处理。",
                                "close", false))))
                .andReturn();
        assertThat(replied.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> repliedRow = jdbcTemplate.queryForMap(
                "SELECT status, response_note, responded_by, responded_at FROM feedback_ticket WHERE id = ?",
                UUID.fromString(ticketId));
        assertThat(repliedRow.get("status")).isEqualTo("REPLIED");
        assertThat(repliedRow.get("response_note")).isEqualTo("已收到您的反馈，我们会尽快核实处理。");
        assertThat(repliedRow.get("responded_by")).isNotNull();
        assertThat(repliedRow.get("responded_at")).isNotNull();

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'FEEDBACK_REPLY' AND ticket_id = ?",
                Integer.class, ticketId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void tooShortContentRejected() throws Exception {
        MvcResult result = submit("OTHER", null, "太短了");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM feedback_ticket WHERE content = ?", Integer.class,
                "太短了")).isZero();
    }

    private String maskedPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
