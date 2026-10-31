package com.waibao.pingdian.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.provider.RealnameProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealnameIntegrationTest extends AbstractIntegrationTest {

    private static final String VALID_ID = "110101199001010015";

    @MockBean
    private RealnameProvider realnameProvider;

    private String loginToken(String phone) throws Exception {
        return registerAndLogin(phone, uniqueIp()).path("token").asText();
    }

    private MvcResult verifyRealname(String token, String idNumber) throws Exception {
        return mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", idNumber))))
                .andReturn();
    }

    @Test
    void threeFailuresLockFor24HoursAndSkipProviderAfterLock() throws Exception {
        when(realnameProvider.verify(anyString(), anyString()))
                .thenReturn(RealnameProvider.RealnameResult.fail("INVALID_ID_NUMBER", "校验位不正确"));
        String token = loginToken(uniquePhone());

        for (int attempt = 1; attempt <= 3; attempt++) {
            MvcResult result = verifyRealname(token, VALID_ID);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode data = body(result).path("data");
            assertThat(data.path("success").asBoolean()).isFalse();
            assertThat(data.path("failCount").asInt()).isEqualTo(attempt);
        }

        MvcResult fourth = verifyRealname(token, VALID_ID);
        assertThat(fourth.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(fourth).path("message").asText()).contains("锁定");
        verify(realnameProvider, times(3)).verify(anyString(), anyString());

        String userId = body(getWithToken("/api/users/me", token)).path("data").path("id").asText();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id_hash, id_masked FROM user_account WHERE id = ?",
                java.util.UUID.fromString(userId));
        assertThat(row.get("id_hash")).isNull();
        assertThat(row.get("id_masked")).isNull();
    }

    @Test
    void successSetsVerifiedStatusAndClearsFailCount() throws Exception {
        when(realnameProvider.verify(anyString(), anyString()))
                .thenReturn(RealnameProvider.RealnameResult.pass());
        String token = loginToken(uniquePhone());

        MvcResult result = verifyRealname(token, VALID_ID);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(result).path("data");
        assertThat(data.path("success").asBoolean()).isTrue();
        assertThat(data.path("realnameStatus").asText()).isEqualTo("VERIFIED");
        assertThat(data.path("failCount").asInt()).isZero();

        String userId = body(getWithToken("/api/users/me", token)).path("data").path("id").asText();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT realname_status, realname_verified_at, realname_fail_count, id_hash, id_masked "
                        + "FROM user_account WHERE id = ?",
                java.util.UUID.fromString(userId));
        assertThat(row.get("realname_status")).isEqualTo("VERIFIED");
        assertThat(row.get("realname_verified_at")).isNotNull();
        assertThat(((Number) row.get("realname_fail_count")).intValue()).isZero();
        assertThat(row.get("id_hash")).isNotNull();
        assertThat((String) row.get("id_hash")).hasSize(64).isNotEqualTo(VALID_ID);
        assertThat(row.get("id_masked")).isEqualTo("110101********0015");
    }
}
