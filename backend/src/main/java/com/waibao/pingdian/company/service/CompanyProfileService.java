package com.waibao.pingdian.company.service;

import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.company.domain.Company;
import com.waibao.pingdian.company.domain.CompanyStatus;
import com.waibao.pingdian.company.domain.Source;
import com.waibao.pingdian.company.domain.UsccRedirect;
import com.waibao.pingdian.company.repo.CompanyAliasRepository;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.company.repo.UsccRedirectRepository;
import com.waibao.pingdian.company.web.dto.CompanyDetailResponse;
import com.waibao.pingdian.company.web.dto.SitemapEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 公司档案读取与 sitemap 清单；旧 USCC 返回 redirectTo 供前台 301。
 */
@Service
public class CompanyProfileService {

    private static final int MAX_REDIRECT_HOPS = 5;

    private final CompanyRepository companyRepository;
    private final CompanyAliasRepository aliasRepository;
    private final UsccRedirectRepository redirectRepository;
    private final JdbcTemplate jdbcTemplate;

    public CompanyProfileService(CompanyRepository companyRepository,
                                 CompanyAliasRepository aliasRepository,
                                 UsccRedirectRepository redirectRepository,
                                 JdbcTemplate jdbcTemplate) {
        this.companyRepository = companyRepository;
        this.aliasRepository = aliasRepository;
        this.redirectRepository = redirectRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CompanyDetailResponse detail(String uscc) {
        Optional<UsccRedirect> redirect = redirectRepository.findById(uscc);
        Optional<Company> company = companyRepository.findById(uscc);
        if (redirect.isEmpty() && company.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公司档案不存在");
        }

        String redirectTo = redirect.map(entry -> resolveFinalUscc(entry.getNewUscc())).orElse(null);
        Company entity = company.orElse(null);
        List<String> aliases = entity == null
                ? List.of()
                : aliasRepository.findByUscc(uscc).stream().map(a -> a.getAliasName()).toList();

        if (entity == null) {
            return new CompanyDetailResponse(
                    uscc, null, null, null, null, null, null, null,
                    CompanyStatus.MERGED, 0, null, false, false, null,
                    aliases, List.of(), 0, redirectTo);
        }

        int reviewCount = entity.getReviewCount();
        BigDecimal avgScore = entity.getAvgScore();
        boolean scoreVisible = entity.isScoreVisible();
        String scoreNote = reviewCount >= 3 ? null : "评价不足，暂不评分";
        return new CompanyDetailResponse(
                entity.getUscc(),
                entity.getName(),
                entity.getShortName(),
                entity.getCity(),
                entity.getIndustry(),
                entity.getCompanyType(),
                entity.getSource(),
                sourceLabel(entity.getSource()),
                entity.getStatus(),
                reviewCount,
                avgScore,
                scoreVisible,
                reviewCount > 0,
                scoreNote,
                aliases,
                dimensions(uscc),
                responseCount(uscc),
                redirectTo);
    }

    public List<SitemapEntry> sitemap(String city, String industry) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.uscc, c.name, c.updated_at
                FROM company c
                WHERE c.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM uscc_redirect r WHERE r.old_uscc = c.uscc)
                """);
        List<Object> args = new ArrayList<>();
        if (city != null && !city.isBlank()) {
            sql.append(" AND c.city = ?");
            args.add(city.trim());
        }
        if (industry != null && !industry.isBlank()) {
            sql.append(" AND c.industry = ?");
            args.add(industry.trim());
        }
        sql.append(" ORDER BY c.name ASC");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new SitemapEntry(
                        rs.getString("uscc"),
                        rs.getString("name"),
                        rs.getTimestamp("updated_at").toInstant()),
                args.toArray());
    }

    private List<CompanyDetailResponse.DimensionScore> dimensions(String uscc) {
        return jdbcTemplate.query("""
                        SELECT rd.dimension_code, AVG(rd.score)::numeric AS avg_score, COUNT(*) AS sample_count
                        FROM review_dimension rd
                        JOIN review r ON r.id = rd.review_id
                        WHERE r.uscc = ? AND r.status = 'PUBLISHED'
                        GROUP BY rd.dimension_code
                        ORDER BY rd.dimension_code
                        """,
                (rs, rowNum) -> {
                    int sampleCount = rs.getInt("sample_count");
                    BigDecimal average = null;
                    if (sampleCount >= 3) {
                        BigDecimal raw = rs.getBigDecimal("avg_score");
                        average = raw == null ? null : raw.setScale(1, RoundingMode.HALF_UP);
                    }
                    return new CompanyDetailResponse.DimensionScore(
                            rs.getString("dimension_code"), average, sampleCount, sampleCount >= 3);
                },
                uscc);
    }

    private int responseCount(String uscc) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enterprise_response WHERE uscc = ? AND status = 'APPROVED'",
                Integer.class, uscc);
        return count == null ? 0 : count;
    }

    private String resolveFinalUscc(String initial) {
        String current = initial;
        for (int i = 0; i < MAX_REDIRECT_HOPS; i++) {
            Optional<UsccRedirect> next = redirectRepository.findById(current);
            if (next.isEmpty()) {
                break;
            }
            current = next.get().getNewUscc();
        }
        return current;
    }

    private String sourceLabel(Source source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case BUSINESS_API -> "工商数据";
            case USER_REPORT -> "用户上报";
            case MANUAL -> "人工补录";
        };
    }
}
