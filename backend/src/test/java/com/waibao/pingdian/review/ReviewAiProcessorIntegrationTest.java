package com.waibao.pingdian.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.review.service.ReviewAiProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReviewAiProcessor 幂等：状态已不是 MACHINE_REVIEWING 时直接跳过，不改状态也不写审计。
 */
class ReviewAiProcessorIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger USCC_SEQ = new AtomicInteger(13000);

    @Autowired
    private ReviewAiProcessor reviewAiProcessor;

    @Test
    void skipsReviewNotInMachineReviewing() throws Exception {
        JsonNode login = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(login.path("user").path("id").asText());
        UUID identityId = jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ?", UUID.class, userId);
        String uscc = "91" + String.format("%016d", USCC_SEQ.incrementAndGet());
        jdbcTemplate.update("INSERT INTO company (uscc, name, city, industry, source, status) "
                + "VALUES (?, ?, '杭州', '互联网', 'MANUAL', 'ACTIVE')", uscc, "AI幂等测试公司" + uscc.substring(10));

        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review (id, uscc, user_id, identity_id, total_score, content,
                                            status, published_at)
                        VALUES (?, ?, ?, ?, 4, '已发布评价正文，长度足够，用于验证重复消息跳过。', 'PUBLISHED', now())
                        """, reviewId, uscc, userId, identityId);

        reviewAiProcessor.process(reviewId, uniqueIp(), "device-ai-idempotent", false);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM review WHERE id = ?",
                String.class, reviewId)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review WHERE id = ? AND ai_flags IS NULL",
                Integer.class, reviewId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_audit WHERE review_id = ?",
                Integer.class, reviewId)).isZero();
    }
}
