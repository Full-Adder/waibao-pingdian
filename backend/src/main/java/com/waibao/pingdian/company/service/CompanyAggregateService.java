package com.waibao.pingdian.company.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 单公司聚合重算：仅统计 PUBLISHED 点评，评价数 ≥3 才展示综合分。
 */
@Service
public class CompanyAggregateService {

    private final JdbcTemplate jdbcTemplate;

    public CompanyAggregateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void recompute(String uscc) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT COUNT(*) AS review_count,
                       AVG(total_score)::numeric AS avg_score
                FROM review
                WHERE uscc = ? AND status = 'PUBLISHED'
                """, uscc);
        int reviewCount = 0;
        BigDecimal avgScore = null;
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            Number count = (Number) row.get("review_count");
            reviewCount = count == null ? 0 : count.intValue();
            Object avg = row.get("avg_score");
            if (avg instanceof BigDecimal decimal) {
                avgScore = decimal.setScale(1, RoundingMode.HALF_UP);
            } else if (avg instanceof Number number) {
                avgScore = new BigDecimal(number.toString()).setScale(1, RoundingMode.HALF_UP);
            }
        }
        jdbcTemplate.update("""
                        UPDATE company
                        SET review_count = ?, avg_score = ?, score_visible = ?, updated_at = now()
                        WHERE uscc = ?
                        """,
                reviewCount, avgScore, reviewCount >= 3, uscc);
    }
}
