package com.waibao.pingdian.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.verification.service.VerificationBadgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerificationIntegrationTest extends AbstractIntegrationTest {

    private static final String USCC = "91110108MA01ABCD12";
    private static final String FORMER_USCC = "91110108MA01EFGH34";
    private static final String VALID_ID = "110101199001010015";

    @Autowired
    private VerificationBadgeService badgeService;

    private String verifiedUserToken(String phone) throws Exception {
        String token = registerAndLogin(phone, uniqueIp()).path("token").asText();
        mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", VALID_ID))))
                .andExpect(status().isOk());
        return token;
    }

    private MvcResult submit(String token, String proofType, String uscc, String start, String end,
                             MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/verifications")
                        .file(file)
                        .param("proofType", proofType)
                        .param("companyUscc", uscc)
                        .param("periodStart", start)
                        .param("periodEnd", end)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private MockMultipartFile pngFile(String name) throws Exception {
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("file", name, "image/png", output.toByteArray());
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "pdf-content-for-test".getBytes());
    }

    @Test
    void acceptsFourProofTypesAndMasksImagesOnly() throws Exception {
        String token = verifiedUserToken(uniquePhone());

        JsonNode labor = submitOk(token, "LABOR_CONTRACT", pngFile("labor.png"));
        submitOk(token, "BADGE", pngFile("badge.png"));
        JsonNode payroll = submitOk(token, "PAYROLL", pdfFile());
        submitOk(token, "COMPANY_EMAIL", new MockMultipartFile(
                "file", "mail.txt", "text/plain", "mail-proof".getBytes()));

        UUID laborId = UUID.fromString(labor.path("id").asText());
        Map<String, Object> imageRow = jdbcTemplate.queryForMap(
                "SELECT file_key, file_hash, masked_file_key FROM user_verification WHERE id = ?", laborId);
        assertThat(imageRow.get("masked_file_key")).isNotNull();
        assertThat(Files.exists(storageRoot().resolve((String) imageRow.get("file_key")))).isTrue();
        assertThat(Files.exists(storageRoot().resolve((String) imageRow.get("masked_file_key")))).isTrue();
        assertThat((String) imageRow.get("file_hash")).hasSize(64);

        UUID pdfId = UUID.fromString(payroll.path("id").asText());
        Map<String, Object> pdfRow = jdbcTemplate.queryForMap(
                "SELECT masked_file_key, file_hash FROM user_verification WHERE id = ?", pdfId);
        assertThat(pdfRow.get("masked_file_key")).isNull();
        assertThat((String) pdfRow.get("file_hash")).hasSize(64);
    }

    @Test
    void rejectNotifiesAndApproveAwardsAndGrantsReviewEligibility() throws Exception {
        JsonNode user = registerAndLogin(uniquePhone(), uniqueIp());
        String token = user.path("token").asText();
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", VALID_ID))))
                .andExpect(status().isOk());

        UUID approveId = UUID.fromString(submitOk(token, "LABOR_CONTRACT", pngFile("a.png")).path("id").asText());
        UUID rejectId = UUID.fromString(submitOk(token, "BADGE", pngFile("b.png")).path("id").asText());

        String admin = adminToken("CONTENT_ADMIN");

        MvcResult list = getWithToken("/api/admin/verifications?status=PENDING&page=1&size=20", admin);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(list).path("data").path("items").size()).isGreaterThan(0);

        mockMvc.perform(post("/api/admin/verifications/" + approveId + "/approve")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(badgeService.isVerifiedForCompany(userId, USCC)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contribution_score FROM user_account WHERE id = ?", Integer.class, userId)).isEqualTo(50);

        mockMvc.perform(post("/api/admin/verifications/" + rejectId + "/reject")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reasonCode", "MATERIAL_BLURRY", "note", "图片模糊"))))
                .andExpect(status().isOk());

        Map<String, Object> rejected = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason_code, reject_note FROM user_verification WHERE id = ?", rejectId);
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("reject_reason_code")).isEqualTo("MATERIAL_BLURRY");
        assertThat(rejected.get("reject_note")).isEqualTo("图片模糊");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE user_id = ? AND type = 'VERIFICATION_REJECTED' "
                        + "AND channel = 'BOTH' AND ref_id = ?",
                Integer.class, userId, rejectId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'VERIFICATION_REJECT' AND target_id = ?",
                Integer.class, rejectId.toString())).isEqualTo(1);

        JsonNode mine = body(getWithToken("/api/verifications/mine", token));
        assertThat(mine.path("data").path("items").size()).isGreaterThanOrEqualTo(2);
        assertThat(mine.toString()).doesNotContain("fileKey");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_verification WHERE id = ? AND status = 'PENDING'",
                Integer.class, approveId)).isZero();
    }

    @Test
    void formerBadgeOutsideWindowRejectedAndMaterialReadIsAuditedAndThresholdAlerted() throws Exception {
        JsonNode user = registerAndLogin(uniquePhone(), uniqueIp());
        String token = user.path("token").asText();
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", VALID_ID))))
                .andExpect(status().isOk());

        MvcResult submitted = submit(token, "LABOR_CONTRACT", FORMER_USCC,
                "2023-01", "2024-01", pngFile("former.png"));
        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
        UUID verificationId = UUID.fromString(body(submitted).path("data").path("id").asText());

        String admin = adminToken("CONTENT_ADMIN");
        mockMvc.perform(post("/api/admin/verifications/" + verificationId + "/approve")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(badgeService.isVerifiedForCompany(userId, FORMER_USCC)).isFalse();
        assertThat(badgeService.badgeFor(userId, FORMER_USCC).orElseThrow().name()).isEqualTo("FORMER");
        assertThatThrownBy(() -> badgeService.assertCanReview(userId, FORMER_USCC))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("12 个月");

        mockMvc.perform(get("/api/admin/materials/VERIFICATION/" + verificationId + "/url")
                        .param("ticketId", verificationId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/materials/VERIFICATION/" + verificationId + "/url")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());

        MvcResult material = mockMvc.perform(get("/api/admin/materials/VERIFICATION/" + verificationId + "/url")
                        .param("ticketId", verificationId.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String url = body(material).path("data").path("url").asText();
        assertThat(url).contains("expires=").contains("sig=");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'MATERIAL_READ' AND target_id = ?",
                Integer.class, verificationId.toString())).isEqualTo(1);

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/admin/materials/VERIFICATION/" + verificationId + "/url")
                            .param("ticketId", verificationId.toString())
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM risk_signal WHERE signal_type = 'MATERIAL_READ_THRESHOLD'",
                Integer.class)).isGreaterThan(0);
    }

    @Test
    void unverifiedUserCannotSubmit() throws Exception {
        String token = registerAndLogin(uniquePhone(), uniqueIp()).path("token").asText();
        MvcResult result = submit(token, "LABOR_CONTRACT", USCC, "2024-01", "2024-12", pngFile("x.png"));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void ownerDeletesMaterialEarlyAndConclusionRemains() throws Exception {
        JsonNode user = registerAndLogin(uniquePhone(), uniqueIp());
        String token = user.path("token").asText();
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", VALID_ID))))
                .andExpect(status().isOk());

        UUID verificationId = UUID.fromString(
                submitOk(token, "LABOR_CONTRACT", pngFile("early.png")).path("id").asText());
        String admin = adminToken("CONTENT_ADMIN");
        mockMvc.perform(post("/api/admin/verifications/" + verificationId + "/approve")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(badgeService.isVerifiedForCompany(userId, USCC)).isTrue();

        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT file_key, masked_file_key FROM user_verification WHERE id = ?", verificationId);
        String originalKey = (String) before.get("file_key");
        String maskedKey = (String) before.get("masked_file_key");
        assertThat(Files.exists(storageRoot().resolve(originalKey))).isTrue();
        assertThat(Files.exists(storageRoot().resolve(maskedKey))).isTrue();

        mockMvc.perform(post("/api/users/me/verifications/" + verificationId + "/delete-material")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Map<String, Object> after = jdbcTemplate.queryForMap("""
                SELECT material_status, material_deleted_at, status, period_start, period_end,
                       proof_type, file_key, masked_file_key
                FROM user_verification WHERE id = ?
                """, verificationId);
        assertThat(after.get("material_status")).isEqualTo("DELETED");
        assertThat(after.get("material_deleted_at")).isNotNull();
        assertThat(after.get("status")).isEqualTo("APPROVED");
        assertThat(after.get("proof_type")).isEqualTo("LABOR_CONTRACT");
        assertThat(after.get("period_start")).isNotNull();
        assertThat(after.get("period_end")).isNotNull();
        assertThat(after.get("file_key")).isNull();
        assertThat(after.get("masked_file_key")).isNull();
        assertThat(Files.exists(storageRoot().resolve(originalKey))).isFalse();
        assertThat(Files.exists(storageRoot().resolve(maskedKey))).isFalse();
        // 删除材料不影响已通过的核验结论与发评资格。
        assertThat(badgeService.isVerifiedForCompany(userId, USCC)).isTrue();

        // 幂等：重复删除返回成功，状态不变。
        mockMvc.perform(post("/api/users/me/verifications/" + verificationId + "/delete-material")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 他人不可删除。
        String otherToken = registerAndLogin(uniquePhone(), uniqueIp()).path("token").asText();
        mockMvc.perform(post("/api/users/me/verifications/" + verificationId + "/delete-material")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    private JsonNode submitOk(String token, String proofType, MockMultipartFile file) throws Exception {
        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"));
        MvcResult result = submit(token, proofType, USCC,
                now.minusMonths(6).toString(), now.plusMonths(6).toString(), file);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(result);
        assertSuccess(body);
        return body.path("data");
    }
}
