package com.waibao.pingdian.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.provider.ObjectStorageProvider;
import com.waibao.pingdian.retention.service.RetentionResult;
import com.waibao.pingdian.retention.service.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16：材料保留三条终点与幂等性。
 */
class RetentionIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(9500);

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private ObjectStorageProvider storageProvider;

    private record Actor(UUID userId, UUID identityId) {
    }

    private record VerificationKeys(UUID id, String fileKey, String maskedFileKey) {
    }

    private Actor newUser() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        UUID identityId = jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ? ORDER BY created_at ASC LIMIT 1",
                UUID.class, userId);
        return new Actor(userId, identityId);
    }

    private String createCompany() {
        String uscc = "91" + String.format("%016d", USCC_SEQ.incrementAndGet());
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                        + "VALUES (?, ?, '杭州', '互联网', 'MANUAL', 'ACTIVE')",
                uscc, "保留测试公司" + uscc.substring(10));
        return uscc;
    }

    private String putFile(String content, String ownerType) {
        return storageProvider.put(content.getBytes(StandardCharsets.UTF_8), "image/png",
                ownerType, UUID.randomUUID().toString());
    }

    private VerificationKeys insertVerification(UUID userId, String uscc, Instant reviewedAt,
                                                Instant createdAt) {
        UUID verificationId = UUID.randomUUID();
        String fileKey = putFile("original-material", "TEST_VERIFICATION");
        String maskedFileKey = putFile("masked-material", "TEST_VERIFICATION_MASKED");
        LocalDate periodStart = LocalDate.now().minusMonths(18);
        LocalDate periodEnd = LocalDate.now().minusMonths(6);
        jdbcTemplate.update("""
                        INSERT INTO user_verification
                            (id, user_id, proof_type, company_uscc, period_start, period_end, status,
                             file_key, masked_file_key, reviewed_at, created_at, material_status)
                        VALUES (?, ?, 'LABOR_CONTRACT', ?, ?, ?, 'APPROVED', ?, ?, ?, ?, 'ACTIVE')
                        """,
                verificationId, userId, uscc, periodStart, periodEnd, fileKey, maskedFileKey,
                reviewedAt == null ? null : Timestamp.from(reviewedAt), Timestamp.from(createdAt));
        return new VerificationKeys(verificationId, fileKey, maskedFileKey);
    }

    private UUID insertReview(UUID userId, UUID identityId, String uscc, String status,
                              Instant createdAt, Instant updatedAt) {
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review
                            (id, uscc, user_id, identity_id, total_score, content, status,
                             published_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 4, ?, ?, ?, ?, ?)
                        """,
                reviewId, uscc, userId, identityId,
                "保留任务测试用评价内容，确认公司整体情况与在职时间段一致。",
                status, Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(updatedAt));
        return reviewId;
    }

    private UUID insertReviewEvidence(UUID reviewId) {
        UUID evidenceId = UUID.randomUUID();
        String fileKey = putFile("review-evidence", "TEST_REVIEW_EVIDENCE");
        jdbcTemplate.update("""
                        INSERT INTO review_evidence (id, review_id, file_key, type, material_status)
                        VALUES (?, ?, ?, 'OTHER', 'ACTIVE')
                        """,
                evidenceId, reviewId, fileKey);
        return evidenceId;
    }

    private String reviewEvidenceFileKey(UUID evidenceId) {
        return jdbcTemplate.queryForObject(
                "SELECT file_key FROM review_evidence WHERE id = ?", String.class, evidenceId);
    }

    private UUID insertComplaint(UUID reviewId, String uscc, String status, Instant createdAt) {
        UUID complaintId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO complaint
                            (id, review_id, claimant_uscc, claimant_contact_cipher, status, created_at)
                        VALUES (?, ?, ?, 'gcm:test-cipher', ?, ?)
                        """,
                complaintId, reviewId, uscc, status, Timestamp.from(createdAt));
        return complaintId;
    }

    private UUID insertComplaintEvidence(UUID complaintId, Instant createdAt) {
        UUID evidenceId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO complaint_evidence
                            (id, complaint_id, side, file_key, material_status, created_at)
                        VALUES (?, ?, 'ENTERPRISE', ?, 'ACTIVE', ?)
                        """,
                evidenceId, complaintId, putFile("complaint-evidence", "TEST_COMPLAINT_EVIDENCE"),
                Timestamp.from(createdAt));
        return evidenceId;
    }

    private Map<String, Object> verificationRow(UUID id) {
        return jdbcTemplate.queryForMap("""
                SELECT material_status, material_deleted_at, status, proof_type, period_start,
                       period_end, file_key, masked_file_key
                FROM user_verification WHERE id = ?
                """, id);
    }

    @Test
    void deletesVerificationMaterialForAllThreeEndpointsAndKeepsConclusion() throws Exception {
        Actor actor = newUser();
        Instant now = Instant.now();

        String noReviewUscc = createCompany();
        VerificationKeys noReview = insertVerification(actor.userId(), noReviewUscc,
                now.minus(400, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS));

        String withdrawnUscc = createCompany();
        VerificationKeys withdrawn = insertVerification(actor.userId(), withdrawnUscc,
                now.minus(60, ChronoUnit.DAYS), now.minus(60, ChronoUnit.DAYS));
        insertReview(actor.userId(), actor.identityId(), withdrawnUscc, "WITHDRAWN",
                now.minus(240, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));

        String hardLimitUscc = createCompany();
        VerificationKeys hardLimit = insertVerification(actor.userId(), hardLimitUscc,
                now.minus(30, ChronoUnit.DAYS), now.minus(760, ChronoUnit.DAYS));

        String liveUscc = createCompany();
        VerificationKeys live = insertVerification(actor.userId(), liveUscc,
                now.minus(400, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS));
        insertReview(actor.userId(), actor.identityId(), liveUscc, "PUBLISHED",
                now.minus(400, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS));

        RetentionResult first = retentionService.run();
        assertThat(first.userVerificationDeleted()).isGreaterThanOrEqualTo(3);

        for (VerificationKeys keys : new VerificationKeys[]{noReview, withdrawn, hardLimit}) {
            Map<String, Object> row = verificationRow(keys.id());
            assertThat(row.get("material_status")).isEqualTo("DELETED");
            assertThat(row.get("material_deleted_at")).isNotNull();
            assertThat(row.get("status")).isEqualTo("APPROVED");
            assertThat(row.get("proof_type")).isEqualTo("LABOR_CONTRACT");
            assertThat(row.get("period_start")).isNotNull();
            assertThat(row.get("period_end")).isNotNull();
            assertThat(row.get("file_key")).isNull();
            assertThat(row.get("masked_file_key")).isNull();
            assertThat(Files.exists(storageRoot().resolve(keys.fileKey()))).isFalse();
            assertThat(Files.exists(storageRoot().resolve(keys.maskedFileKey()))).isFalse();
        }

        UUID liveId = live.id();
        Map<String, Object> liveRow = verificationRow(liveId);
        assertThat(liveRow.get("material_status")).isEqualTo("ACTIVE");
        assertThat(Files.exists(storageRoot().resolve(live.fileKey()))).isTrue();
        assertThat(Files.exists(storageRoot().resolve(live.maskedFileKey()))).isTrue();

        RetentionResult second = retentionService.run();
        assertThat(second.userVerificationDeleted()).isZero();
        assertThat(verificationRow(liveId).get("material_status")).isEqualTo("ACTIVE");
    }

    @Test
    void deletesReviewAndComplaintEvidenceByRetentionWindows() throws Exception {
        Actor actor = newUser();
        Instant now = Instant.now();

        String withdrawnUscc = createCompany();
        UUID withdrawnReview = insertReview(actor.userId(), actor.identityId(), withdrawnUscc,
                "WITHDRAWN", now.minus(240, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));
        UUID withdrawnEvidence = insertReviewEvidence(withdrawnReview);

        String hardLimitUscc = createCompany();
        UUID hardLimitReview = insertReview(actor.userId(), actor.identityId(), hardLimitUscc,
                "PUBLISHED", now.minus(760, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS));
        UUID hardLimitEvidence = insertReviewEvidence(hardLimitReview);

        String recentUscc = createCompany();
        UUID recentReview = insertReview(actor.userId(), actor.identityId(), recentUscc,
                "PUBLISHED", now.minus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        UUID recentEvidence = insertReviewEvidence(recentReview);

        String ruledUscc = createCompany();
        UUID ruledReview = insertReview(actor.userId(), actor.identityId(), ruledUscc,
                "DISPUTED", now.minus(240, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));
        UUID ruledComplaint = insertComplaint(ruledReview, ruledUscc, "RULED_USER_WIN",
                now.minus(240, ChronoUnit.DAYS));
        UUID ruledEvidence = insertComplaintEvidence(ruledComplaint, now.minus(240, ChronoUnit.DAYS));

        String collectingUscc = createCompany();
        UUID collectingReview = insertReview(actor.userId(), actor.identityId(), collectingUscc,
                "DISPUTED", now.minus(300, ChronoUnit.DAYS), now.minus(300, ChronoUnit.DAYS));
        UUID collectingComplaint = insertComplaint(collectingReview, collectingUscc,
                "EVIDENCE_COLLECTING", now.minus(300, ChronoUnit.DAYS));
        UUID collectingEvidence = insertComplaintEvidence(collectingComplaint, now.minus(300, ChronoUnit.DAYS));

        RetentionResult result = retentionService.run();
        assertThat(result.reviewEvidenceDeleted()).isGreaterThanOrEqualTo(2);
        assertThat(result.complaintEvidenceDeleted()).isGreaterThanOrEqualTo(1);

        for (UUID evidenceId : new UUID[]{withdrawnEvidence, hardLimitEvidence}) {
            String fileKey = reviewEvidenceFileKey(evidenceId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT material_status FROM review_evidence WHERE id = ?", String.class, evidenceId))
                    .isEqualTo("DELETED");
            assertThat(Files.exists(storageRoot().resolve(fileKey))).isFalse();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT material_status FROM review_evidence WHERE id = ?", String.class, recentEvidence))
                .isEqualTo("ACTIVE");
        assertThat(Files.exists(storageRoot().resolve(reviewEvidenceFileKey(recentEvidence)))).isTrue();

        Map<String, Object> ruledRow = jdbcTemplate.queryForMap(
                "SELECT material_status, material_deleted_at, file_key FROM complaint_evidence WHERE id = ?",
                ruledEvidence);
        assertThat(ruledRow.get("material_status")).isEqualTo("DELETED");
        assertThat(ruledRow.get("material_deleted_at")).isNotNull();
        assertThat(Files.exists(storageRoot().resolve((String) ruledRow.get("file_key")))).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT material_status FROM complaint_evidence WHERE id = ?", String.class, collectingEvidence))
                .isEqualTo("ACTIVE");
    }
}
