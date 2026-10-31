package com.waibao.pingdian.retention.service;

import com.waibao.pingdian.provider.ObjectStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 材料保留三条终点（R19 / PRD 12.4）：
 * 1. 核验通过后 12 个月内未对该 USCC 发布任何评价；
 * 2. 该用户对该 USCC 的评价已撤回/下架超过 6 个月；
 * 3. 材料创建满 24 个月的硬上限。
 * 任一触发即删除原件与脱敏副本，只保留核验结论（状态、时间段、类型）。
 * 任务幂等：只处理 material_status=ACTIVE 的记录；单个文件删除失败只记日志，下次重试。
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorageProvider storageProvider;

    public RetentionService(JdbcTemplate jdbcTemplate, ObjectStorageProvider storageProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageProvider = storageProvider;
    }

    public RetentionResult run() {
        int verificationDeleted = deleteExpiredVerificationMaterials();
        int reviewEvidenceDeleted = deleteExpiredReviewEvidence();
        int complaintEvidenceDeleted = deleteExpiredComplaintEvidence();
        log.info("retention_run userVerificationDeleted={} reviewEvidenceDeleted={} "
                        + "complaintEvidenceDeleted={}", verificationDeleted,
                reviewEvidenceDeleted, complaintEvidenceDeleted);
        return new RetentionResult(verificationDeleted, reviewEvidenceDeleted, complaintEvidenceDeleted);
    }

    private int deleteExpiredVerificationMaterials() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT v.id, v.file_key, v.masked_file_key
                FROM user_verification v
                WHERE v.material_status = 'ACTIVE'
                  AND (
                    (v.status = 'APPROVED'
                      AND COALESCE(v.reviewed_at, v.created_at) < now() - interval '12 months'
                      AND NOT EXISTS (
                        SELECT 1 FROM review r
                        WHERE r.user_id = v.user_id AND r.uscc = v.company_uscc))
                    OR EXISTS (
                      SELECT 1 FROM review r
                      WHERE r.user_id = v.user_id AND r.uscc = v.company_uscc
                        AND r.status IN ('WITHDRAWN', 'REMOVED')
                        AND r.updated_at < now() - interval '6 months')
                    OR v.created_at < now() - interval '24 months'
                  )
                """);
        int deleted = 0;
        for (Map<String, Object> row : rows) {
            UUID id = toUuid(row.get("id"));
            if (!deleteFiles(id, (String) row.get("file_key"), (String) row.get("masked_file_key"))) {
                continue;
            }
            int updated = jdbcTemplate.update("""
                    UPDATE user_verification
                    SET material_status = 'DELETED', material_deleted_at = now(),
                        file_key = NULL, masked_file_key = NULL
                    WHERE id = ? AND material_status = 'ACTIVE'
                    """, id);
            if (updated > 0) {
                deleted++;
            }
        }
        return deleted;
    }

    private int deleteExpiredReviewEvidence() {
        // review_evidence 无 created_at 列，24 个月硬上限以所属 review 的 created_at 近似。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.id, e.file_key
                FROM review_evidence e
                JOIN review r ON r.id = e.review_id
                WHERE e.material_status = 'ACTIVE'
                  AND (
                    (r.status IN ('WITHDRAWN', 'REMOVED')
                      AND r.updated_at < now() - interval '6 months')
                    OR r.created_at < now() - interval '24 months'
                  )
                """);
        int deleted = 0;
        for (Map<String, Object> row : rows) {
            UUID id = toUuid(row.get("id"));
            if (!deleteFiles(id, (String) row.get("file_key"))) {
                continue;
            }
            int updated = jdbcTemplate.update("""
                    UPDATE review_evidence SET material_status = 'DELETED'
                    WHERE id = ? AND material_status = 'ACTIVE'
                    """, id);
            if (updated > 0) {
                deleted++;
            }
        }
        return deleted;
    }

    private int deleteExpiredComplaintEvidence() {
        // 投诉表无 updated_at 列，以 created_at / 复核 / 裁定的最晚时间近似“最后更新时间”。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.id, e.file_key
                FROM complaint_evidence e
                JOIN complaint c ON c.id = e.complaint_id
                WHERE e.material_status = 'ACTIVE'
                  AND (
                    (c.status IN ('RULED_USER_WIN', 'RULED_COMPANY_WIN', 'RULED_INSUFFICIENT')
                      AND GREATEST(c.created_at,
                                   COALESCE(c.dual_review_at, c.created_at),
                                   COALESCE(c.proposed_at, c.created_at))
                          < now() - interval '6 months')
                    OR e.created_at < now() - interval '24 months'
                  )
                """);
        int deleted = 0;
        for (Map<String, Object> row : rows) {
            UUID id = toUuid(row.get("id"));
            if (!deleteFiles(id, (String) row.get("file_key"))) {
                continue;
            }
            int updated = jdbcTemplate.update("""
                    UPDATE complaint_evidence
                    SET material_status = 'DELETED', material_deleted_at = now()
                    WHERE id = ? AND material_status = 'ACTIVE'
                    """, id);
            if (updated > 0) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * 逐个删除文件；任一失败即放弃本条记录的 DB 更新，留待下次任务重试。
     */
    private boolean deleteFiles(UUID id, String... fileKeys) {
        boolean success = true;
        for (String fileKey : fileKeys) {
            if (fileKey == null || fileKey.isBlank()) {
                continue;
            }
            try {
                storageProvider.delete(fileKey);
            } catch (RuntimeException ex) {
                success = false;
                log.warn("retention_delete_failed id={} fileKey={} error={}",
                        id, fileKey, ex.getClass().getSimpleName());
            }
        }
        return success;
    }

    private UUID toUuid(Object value) {
        return UUID.fromString(value.toString());
    }
}
