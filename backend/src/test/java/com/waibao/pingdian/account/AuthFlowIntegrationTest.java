package com.waibao.pingdian.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.account.service.SmsCodeService;
import com.waibao.pingdian.config.AuthProperties;
import com.waibao.pingdian.provider.SmsProvider;
import com.waibao.pingdian.security.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private SmsProvider smsProvider;

    @Autowired
    private AuthProperties authProperties;

    private MvcResult sendSms(String phone) throws Exception {
        return mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "scene", "LOGIN")))
                        .header("X-Forwarded-For", uniqueIp()))
                .andReturn();
    }

    private MvcResult login(String phone, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "code", "123456", "acceptedTerms", true)))
                        .header("X-Forwarded-For", ip))
                .andReturn();
    }

    private JsonNode loginOk(String phone, String ip) throws Exception {
        JsonNode send = body(sendSms(phone));
        assertSuccess(send);
        MvcResult result = login(phone, ip);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertSuccess(body);
        return body.path("data");
    }

    @Test
    void registerLoginAndFetchMe() throws Exception {
        String phone = uniquePhone();
        JsonNode data = loginOk(phone, uniqueIp());

        String token = data.path("token").asText();
        JsonNode user = data.path("user");
        assertThat(user.path("id").asText()).isNotBlank();
        assertThat(user.path("phone").asText()).contains("****");
        assertThat(user.path("realnameStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(user.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(user.path("identity").path("alias").asText()).startsWith("匿名·");
        assertThat(user.path("identity").path("alias").asText()).hasSize(7);

        MvcResult me = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meBody = body(me);
        assertSuccess(meBody);
        assertThat(meBody.path("data").path("id").asText()).isEqualTo(user.path("id").asText());
        assertThat(meBody.path("data").path("contributionScore").asInt()).isZero();
    }

    @Test
    void resendSmsWithinSixtySecondsIsRateLimited() throws Exception {
        String phone = uniquePhone();
        MvcResult first = sendSms(phone);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult second = sendSms(phone);
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        JsonNode body = body(second);
        assertThat(body.path("code").asInt()).isEqualTo(429);
        assertThat(body.path("message").asText()).contains("秒");
    }

    @Test
    void fourthRegistrationFromSameIpIsRateLimited() throws Exception {
        String ip = "198.51.100.77";
        for (int i = 0; i < 3; i++) {
            loginOk(uniquePhone(), ip);
        }
        String fourthPhone = uniquePhone();
        JsonNode send = body(sendSms(fourthPhone));
        assertSuccess(send);

        MvcResult fourth = login(fourthPhone, ip);
        assertThat(fourth.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(fourth).path("code").asInt()).isEqualTo(429);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_account WHERE phone_hash = ?",
                Integer.class, cryptoService.hmacSha256Hex(fourthPhone))).isZero();
    }

    @Test
    void secondDeviceLoginInvalidatesOldToken() throws Exception {
        String phone = uniquePhone();
        String ip = uniqueIp();
        String firstToken = loginOk(phone, ip).path("token").asText();

        smsCodeService.clearAll();
        String secondToken = loginOk(phone, ip).path("token").asText();
        assertThat(secondToken).isNotEqualTo(firstToken);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());
    }

    @Test
    void guestWithoutAuthorizationGetsForbidden() throws Exception {
        MvcResult me = mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = body(me);
        assertThat(body.path("code").asInt()).isEqualTo(403);
        assertThat(body.path("message").asText()).contains("请先登录");

        mockMvc.perform(get("/api/reviews/mine")).andExpect(status().isForbidden());
    }

    @Test
    void invalidTokenGetsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminLoginSucceedsAndWrongPasswordFails() throws Exception {
        String account = "super_" + uniquePhone();
        String password = "adminPass123";
        jdbcTemplate.update("""
                        INSERT INTO admin_user (account, password_hash, role, status)
                        VALUES (?, ?, 'SUPER_ADMIN', 'ACTIVE')
                        """,
                account, passwordEncoder.encode(password));

        MvcResult ok = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("account", account, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = body(ok);
        assertSuccess(body);
        assertThat(body.path("data").path("role").asText()).isEqualTo("SUPER_ADMIN");
        String token = body.path("data").path("token").asText();

        MvcResult me = mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(me).path("data").path("account").asText()).isEqualTo(account);

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("account", account, "password", "wrongPassword123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void databaseStoresCipherAndHashNotPlaintext() throws Exception {
        String phone = uniquePhone();
        loginOk(phone, uniqueIp());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT phone_cipher, phone_hash FROM user_account WHERE phone_hash = ?",
                cryptoService.hmacSha256Hex(phone));
        String cipher = (String) row.get("phone_cipher");
        String hash = (String) row.get("phone_hash");
        assertThat(cipher).startsWith("gcm:").doesNotContain(phone);
        assertThat(hash).hasSize(64).doesNotContain(phone);
    }

    @Test
    void smsCodeIsSharedAcrossInstancesAndExpiresInFiveMinutes() throws Exception {
        String phone = uniquePhone();
        JsonNode send = body(sendSms(phone));
        assertSuccess(send);
        assertThat(send.path("data").path("expiresInSeconds").asInt()).isEqualTo(300);

        String codeKey = redisSupport.key("sms:code:" + phone);
        assertThat(redisSupport.find(codeKey)).contains("123456");
        assertThat(redisSupport.remainingSeconds(codeKey)).isBetween(290L, 300L);

        // 另一个服务实例（模拟另一台应用节点）读取同一 Redis 状态完成校验。
        SmsCodeService anotherInstance = new SmsCodeService(smsProvider, authProperties, redisSupport);
        anotherInstance.verify(phone, "123456");
        assertThat(redisSupport.find(codeKey)).isEmpty();
    }

    @Test
    void smsCooldownIsSixtySecondsAndSecondSendIsRateLimited() throws Exception {
        String phone = uniquePhone();
        assertThat(sendSms(phone).getResponse().getStatus()).isEqualTo(200);
        String cooldownKey = redisSupport.key("sms:cooldown:" + phone);
        assertThat(redisSupport.remainingSeconds(cooldownKey)).isBetween(55L, 60L);

        MvcResult second = sendSms(phone);
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(second).path("message").asText()).contains("秒");
    }

    @Test
    void sessionFallsBackToDatabaseWhenRedisKeyIsMissing() throws Exception {
        String phone = uniquePhone();
        JsonNode data = loginOk(phone, uniqueIp());
        String token = data.path("token").asText();
        UUID userId = UUID.fromString(data.path("user").path("id").asText());

        String sessionKey = redisSupport.key("session:user:" + userId);
        assertThat(redisSupport.find(sessionKey)).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT session_jti FROM user_account WHERE id = ?", String.class, userId))
                .isEqualTo(redisSupport.find(sessionKey).orElse(null));

        // 模拟 Redis 会话丢失（重启/旧数据）：回退数据库 session_jti，用户不掉线。
        redisSupport.delete(sessionKey);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
