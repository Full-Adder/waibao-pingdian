package com.waibao.pingdian.health;

import com.waibao.pingdian.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 健康检查：本机 PostgreSQL 与 Redis 均可用时返回 200，字段只暴露 UP/DOWN。
 */
class HealthIntegrationTest extends AbstractIntegrationTest {

    @Test
    void healthReportsDatabaseAndRedisUp() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertSuccess(body);
        assertThat(body.path("data").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("data").path("database").asText()).isEqualTo("UP");
        assertThat(body.path("data").path("redis").asText()).isEqualTo("UP");
        // 不泄露密码/连接串等信息。
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password", "6379", "jdbc:");
    }
}
