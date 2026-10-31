package com.waibao.pingdian.track;

import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 12：访问埋点 204、visitorId+path 1 分钟去重、不记录 IP。
 */
class TrackIntegrationTest extends AbstractIntegrationTest {

    private MvcResult track(String visitorId, String path) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitorId", visitorId);
        body.put("path", path);
        body.put("userAgent", "Mozilla/5.0 test");
        return mockMvc.perform(post("/api/track/visit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void visitBeaconReturns204AndDeduplicatesWithinOneMinute() throws Exception {
        String visitorId = "visitor-" + UUID.randomUUID();
        String path = "/companies/demo";

        assertThat(track(visitorId, path).getResponse().getStatus()).isEqualTo(204);
        assertThat(track(visitorId, path).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM visit_event WHERE visitor_id = ? AND path = ?",
                Integer.class, visitorId, path)).isEqualTo(1);

        assertThat(track(visitorId, "/rankings").getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM visit_event WHERE visitor_id = ?",
                Integer.class, visitorId)).isEqualTo(2);
    }

    @Test
    void visitBeaconRejectsMissingVisitorId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/track/visit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "/company")))
                ).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void visitDedupeKeyLivesInRedisForOneMinute() throws Exception {
        String visitorId = "visitor-" + UUID.randomUUID();
        String path = "/companies/redis-dedupe";
        assertThat(track(visitorId, path).getResponse().getStatus()).isEqualTo(204);

        String dedupeKey = redisSupport.key("visit:dedupe:" + visitorId + ":" + sha256(path));
        assertThat(redisSupport.find(dedupeKey)).contains("1");
        assertThat(redisSupport.remainingSeconds(dedupeKey)).isBetween(55L, 60L);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
