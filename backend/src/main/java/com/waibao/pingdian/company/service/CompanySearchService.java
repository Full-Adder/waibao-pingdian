package com.waibao.pingdian.company.service;

import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.domain.Source;
import com.waibao.pingdian.company.web.dto.CompanySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司搜索：名称 / 简称 / 别名分别走 pg_trgm GIN 索引后 UNION 去重，
 * 避免 OR + EXISTS 被规划成全表扫描；结果按相似度、评价数降序，未评价公司同样返回。
 */
@Service
public class CompanySearchService {

    private static final Logger log = LoggerFactory.getLogger(CompanySearchService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private static final String SIMILARITY_EXPRESSION = """
            GREATEST(
                similarity(c.name, ?),
                COALESCE(similarity(c.short_name, ?), 0),
                COALESCE((SELECT MAX(similarity(a.alias_name, ?))
                          FROM company_alias a WHERE a.uscc = c.uscc), 0)
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public CompanySearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResult<CompanySummary> search(String keyword, String city, String industry,
                                             int page, int size, String visitorId) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        boolean hasKeyword = !trimmedKeyword.isEmpty();

        List<Object> baseArgs = new ArrayList<>();
        StringBuilder baseFilter = new StringBuilder("c.status = 'ACTIVE'");
        if (city != null && !city.isBlank()) {
            baseFilter.append(" AND c.city = ?");
            baseArgs.add(city.trim());
        }
        if (industry != null && !industry.isBlank()) {
            baseFilter.append(" AND c.industry = ?");
            baseArgs.add(industry.trim());
        }

        String countSql;
        String selectSql;
        List<Object> countArgs = new ArrayList<>();
        List<Object> selectArgs = new ArrayList<>();
        if (hasKeyword) {
            String pattern = "%" + escapeLike(trimmedKeyword) + "%";
            List<Object> matchedArgs = new ArrayList<>();
            String filter = baseFilter.toString();
            String cte = "WITH matched AS ("
                    + companyBranch(filter, baseArgs, matchedArgs, "c.name", pattern)
                    + " UNION "
                    + companyBranch(filter, baseArgs, matchedArgs, "c.short_name", pattern)
                    + " UNION "
                    + "SELECT a.uscc FROM company_alias a JOIN company c ON c.uscc = a.uscc "
                    + "WHERE " + filter + " AND a.alias_name ILIKE ?"
                    + ") ";
            // 别名分支参数：基础过滤条件 + keyword 模式。
            matchedArgs.addAll(baseArgs);
            matchedArgs.add(pattern);

            countArgs.addAll(matchedArgs);
            countSql = cte + "SELECT COUNT(*) FROM matched";

            selectArgs.addAll(matchedArgs);
            selectSql = cte
                    + "SELECT c.uscc, c.name, c.short_name, c.city, c.industry, c.company_type, "
                    + "c.source, c.review_count, c.avg_score, c.score_visible, "
                    + SIMILARITY_EXPRESSION + " AS sim "
                    + "FROM company c JOIN matched m ON m.uscc = c.uscc "
                    + "ORDER BY sim DESC, c.review_count DESC, c.name ASC "
                    + "LIMIT ? OFFSET ?";
            selectArgs.add(trimmedKeyword);
            selectArgs.add(trimmedKeyword);
            selectArgs.add(trimmedKeyword);
        } else {
            countArgs.addAll(baseArgs);
            countSql = "SELECT COUNT(*) FROM company c WHERE " + baseFilter;

            selectArgs.addAll(baseArgs);
            selectSql = "SELECT c.uscc, c.name, c.short_name, c.city, c.industry, c.company_type, "
                    + "c.source, c.review_count, c.avg_score, c.score_visible, 0 AS sim "
                    + "FROM company c WHERE " + baseFilter + " "
                    + "ORDER BY c.review_count DESC, c.name ASC "
                    + "LIMIT ? OFFSET ?";
        }
        selectArgs.add(safeSize);
        selectArgs.add((long) (safePage - 1) * safeSize);

        long total = jdbcTemplate.queryForObject(countSql, Long.class, countArgs.toArray());
        List<CompanySummary> items = jdbcTemplate.query(selectSql,
                (rs, rowNum) -> mapSummary(rs), selectArgs.toArray());
        recordSearchLog(trimmedKeyword, items.size(), city, industry, visitorId);
        return PageResult.of(items, safePage, safeSize, total);
    }

    public CompanySummary summaryOf(String uscc) {
        List<CompanySummary> rows = jdbcTemplate.query("""
                        SELECT c.uscc, c.name, c.short_name, c.city, c.industry, c.company_type,
                               c.source, c.review_count, c.avg_score, c.score_visible
                        FROM company c WHERE c.uscc = ?
                        """,
                (rs, rowNum) -> mapSummary(rs), uscc);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String companyBranch(String filter, List<Object> baseArgs, List<Object> matchedArgs,
                                 String column, String pattern) {
        matchedArgs.addAll(baseArgs);
        matchedArgs.add(pattern);
        return "SELECT c.uscc FROM company c WHERE " + filter + " AND " + column + " ILIKE ?";
    }

    private CompanySummary mapSummary(ResultSet rs) throws SQLException {
        int reviewCount = rs.getInt("review_count");
        BigDecimal avgScore = rs.getBigDecimal("avg_score");
        return new CompanySummary(
                rs.getString("uscc"),
                rs.getString("name"),
                rs.getString("short_name"),
                rs.getString("city"),
                rs.getString("industry"),
                rs.getString("company_type"),
                parseSource(rs.getString("source")),
                reviewCount,
                avgScore,
                rs.getBoolean("score_visible"),
                reviewCount > 0);
    }

    private Source parseSource(String value) {
        try {
            return value == null ? Source.MANUAL : Source.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Source.MANUAL;
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private void recordSearchLog(String keyword, int resultCount, String city, String industry,
                                 String visitorId) {
        try {
            String storedKeyword = keyword;
            if (storedKeyword != null && storedKeyword.length() > 255) {
                storedKeyword = storedKeyword.substring(0, 255);
            }
            jdbcTemplate.update("""
                            INSERT INTO search_log (keyword, result_count, city, industry, visitor_id)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    storedKeyword,
                    resultCount,
                    blankToNull(city),
                    blankToNull(industry),
                    blankToNull(visitorId));
        } catch (RuntimeException ex) {
            // 搜索日志失败不影响搜索主流程。
            log.warn("record search log failed: {}", ex.getClass().getSimpleName());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
