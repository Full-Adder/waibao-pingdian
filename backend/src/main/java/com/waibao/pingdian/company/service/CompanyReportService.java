package com.waibao.pingdian.company.service;

import com.waibao.pingdian.company.domain.CandidateStatus;
import com.waibao.pingdian.company.domain.CompanyCandidate;
import com.waibao.pingdian.company.repo.CompanyCandidateRepository;
import com.waibao.pingdian.company.web.dto.CompanyReportRequest;
import com.waibao.pingdian.company.web.dto.CompanyReportResult;
import com.waibao.pingdian.company.web.dto.CompanySummary;
import com.waibao.pingdian.security.CurrentUserService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 用户上报公司：先精确匹配（含别名），再按 trigram 最高分；命中不新建，未命中只进候选池。
 */
@Service
public class CompanyReportService {

    private static final double TRIGRAM_MATCH_THRESHOLD = 0.5;
    private static final Duration DUPLICATE_WINDOW = Duration.ofHours(24);

    private final CurrentUserService currentUserService;
    private final CompanyCandidateRepository candidateRepository;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final CompanySearchService searchService;

    public CompanyReportService(CurrentUserService currentUserService,
                                CompanyCandidateRepository candidateRepository,
                                NamedParameterJdbcTemplate namedJdbcTemplate,
                                CompanySearchService searchService) {
        this.currentUserService = currentUserService;
        this.candidateRepository = candidateRepository;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.searchService = searchService;
    }

    @Transactional
    public CompanyReportResult report(CompanyReportRequest request) {
        var userId = currentUserService.getUserId();
        String normalized = CompanyNameNormalizer.normalize(request.name());

        String matchedUscc = findExactMatch(normalized);
        if (matchedUscc == null) {
            matchedUscc = findTrigramMatch(normalized);
        }
        if (matchedUscc != null) {
            CompanySummary company = searchService.summaryOf(matchedUscc);
            if (company != null) {
                return new CompanyReportResult(true, false, company, null);
            }
        }

        CompanyCandidate existing = findRecentDuplicate(normalized);
        if (existing != null) {
            return new CompanyReportResult(false, true, null, existing.getId());
        }

        CompanyCandidate candidate = new CompanyCandidate();
        candidate.setName(request.name().trim());
        candidate.setCity(blankToNull(request.city()));
        candidate.setIndustry(blankToNull(request.industry()));
        candidate.setReporterUserId(userId);
        candidate.setStatus(CandidateStatus.PENDING);
        candidateRepository.save(candidate);
        return new CompanyReportResult(false, true, null, candidate.getId());
    }

    private String findExactMatch(String normalized) {
        List<Map<String, Object>> rows = namedJdbcTemplate.queryForList("""
                SELECT c.uscc
                FROM company c
                WHERE c.status = 'ACTIVE'
                  AND (
                    LOWER(regexp_replace(c.name, '[[:space:]（）()]', '', 'g')) = LOWER(:normalized)
                    OR LOWER(regexp_replace(COALESCE(c.short_name, ''), '[[:space:]（）()]', '', 'g'))
                       = LOWER(:normalized)
                    OR EXISTS (
                        SELECT 1 FROM company_alias a
                        WHERE a.uscc = c.uscc
                          AND LOWER(regexp_replace(a.alias_name, '[[:space:]（）()]', '', 'g'))
                              = LOWER(:normalized)
                    )
                  )
                ORDER BY c.review_count DESC
                LIMIT 1
                """, new MapSqlParameterSource("normalized", normalized));
        return rows.isEmpty() ? null : (String) rows.get(0).get("uscc");
    }

    private String findTrigramMatch(String normalized) {
        if (normalized.length() < 3) {
            return null;
        }
        List<Map<String, Object>> rows = namedJdbcTemplate.queryForList("""
                SELECT c.uscc,
                       GREATEST(
                           similarity(c.name, :keyword),
                           COALESCE(similarity(c.short_name, :keyword), 0),
                           COALESCE((SELECT MAX(similarity(a.alias_name, :keyword))
                                     FROM company_alias a WHERE a.uscc = c.uscc), 0)
                       ) AS sim
                FROM company c
                WHERE c.status = 'ACTIVE'
                  AND (
                    c.name % :keyword
                    OR c.short_name % :keyword
                    OR EXISTS (SELECT 1 FROM company_alias a
                               WHERE a.uscc = c.uscc AND a.alias_name % :keyword)
                  )
                ORDER BY sim DESC
                LIMIT 1
                """, new MapSqlParameterSource("keyword", normalized));
        if (rows.isEmpty()) {
            return null;
        }
        Object similarity = rows.get(0).get("sim");
        double score = similarity instanceof Number number ? number.doubleValue() : 0;
        return score >= TRIGRAM_MATCH_THRESHOLD ? (String) rows.get(0).get("uscc") : null;
    }

    private CompanyCandidate findRecentDuplicate(String normalized) {
        List<CompanyCandidate> recent = candidateRepository.findByCreatedAtAfter(
                Instant.now().minus(DUPLICATE_WINDOW));
        for (CompanyCandidate candidate : recent) {
            if (normalized.equals(CompanyNameNormalizer.normalize(candidate.getName()))) {
                return candidate;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
