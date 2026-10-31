package com.waibao.pingdian.company.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.domain.CandidateStatus;
import com.waibao.pingdian.company.domain.Company;
import com.waibao.pingdian.company.domain.CompanyCandidate;
import com.waibao.pingdian.company.domain.CompanyStatus;
import com.waibao.pingdian.company.domain.Source;
import com.waibao.pingdian.company.repo.CompanyCandidateRepository;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.company.web.dto.CandidateResolveRequest;
import com.waibao.pingdian.company.web.dto.CompanyCandidateItem;
import com.waibao.pingdian.company.web.dto.CompanySummary;
import com.waibao.pingdian.company.web.dto.CompanyUpdateRequest;
import com.waibao.pingdian.provider.BusinessDataProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理端公司档案、候选池与合并。所有写操作都落 admin_audit_log。
 */
@Service
public class AdminCompanyService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int PROVIDER_PAGE_SIZE = 200;
    private static final int MAX_PROVIDER_PAGES = 100;
    private static final String USCC_PATTERN = "^[0-9A-Z]{18}$";

    private final CompanyRepository companyRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanySearchService searchService;
    private final CompanyAggregateService aggregateService;
    private final BusinessDataProvider businessDataProvider;
    private final AdminAuditService auditService;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminCompanyService(CompanyRepository companyRepository,
                               CompanyCandidateRepository candidateRepository,
                               CompanySearchService searchService,
                               CompanyAggregateService aggregateService,
                               BusinessDataProvider businessDataProvider,
                               AdminAuditService auditService,
                               NamedParameterJdbcTemplate namedJdbcTemplate,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper) {
        this.companyRepository = companyRepository;
        this.candidateRepository = candidateRepository;
        this.searchService = searchService;
        this.aggregateService = aggregateService;
        this.businessDataProvider = businessDataProvider;
        this.auditService = auditService;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public PageResult<CompanySummary> listCompanies(String keyword, String status, String city,
                                                    String industry, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String trimmedKeyword = keyword == null ? "" : keyword.trim();

        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!trimmedKeyword.isEmpty()) {
            params.addValue("pattern", "%" + escapeLike(trimmedKeyword) + "%");
            conditions.add("""
                    (c.name ILIKE :pattern
                     OR c.short_name ILIKE :pattern
                     OR EXISTS (SELECT 1 FROM company_alias a
                                WHERE a.uscc = c.uscc AND a.alias_name ILIKE :pattern))
                    """);
        }
        if (status != null && !status.isBlank()) {
            try {
                params.addValue("status", CompanyStatus.valueOf(status.trim().toUpperCase()).name());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "公司状态不正确");
            }
            conditions.add("c.status = :status");
        }
        if (city != null && !city.isBlank()) {
            params.addValue("city", city.trim());
            conditions.add("c.city = :city");
        }
        if (industry != null && !industry.isBlank()) {
            params.addValue("industry", industry.trim());
            conditions.add("c.industry = :industry");
        }
        String whereClause = conditions.isEmpty() ? "TRUE" : String.join(" AND ", conditions);

        long total = namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company c WHERE " + whereClause, params, Long.class);
        params.addValue("limit", safeSize);
        params.addValue("offset", (long) (safePage - 1) * safeSize);
        List<CompanySummary> items = namedJdbcTemplate.query("""
                        SELECT c.uscc, c.name, c.short_name, c.city, c.industry, c.company_type,
                               c.source, c.review_count, c.avg_score, c.score_visible
                        FROM company c
                        WHERE %s
                        ORDER BY c.updated_at DESC, c.name ASC
                        LIMIT :limit OFFSET :offset
                        """.formatted(whereClause),
                params, this::mapSummary);
        return PageResult.of(items, safePage, safeSize, total);
    }

    @Transactional
    public void updateCompany(String uscc, CompanyUpdateRequest request, AdminUser admin, String ip) {
        Company company = companyRepository.findById(uscc)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "公司档案不存在"));
        String beforeHash = companySnapshot(uscc);

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
            }
            company.setName(request.name().trim());
        }
        if (request.shortName() != null) {
            company.setShortName(blankToNull(request.shortName()));
        }
        if (request.city() != null) {
            company.setCity(blankToNull(request.city()));
        }
        if (request.industry() != null) {
            company.setIndustry(blankToNull(request.industry()));
        }
        if (request.companyType() != null) {
            company.setCompanyType(blankToNull(request.companyType()));
        }
        if (request.status() != null) {
            company.setStatus(request.status());
        }
        companyRepository.saveAndFlush(company);

        String afterHash = companySnapshot(uscc);
        String detail = detailJson(request.reason(), request.note(), null);
        auditService.record(admin.getId(), admin.getRole(), "COMPANY_UPDATE", "COMPANY",
                uscc, reasonCode(request.reason(), "COMPANY_UPDATE"), detail, request.ticketId(), ip,
                beforeHash, afterHash);
    }

    public PageResult<CompanyCandidateItem> listCandidates(CandidateStatus status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CompanyCandidate> result = status == null
                ? candidateRepository.findAll(pageable)
                : candidateRepository.findByStatus(status, pageable);
        List<CompanyCandidateItem> items = result.getContent().stream()
                .map(candidate -> new CompanyCandidateItem(
                        candidate.getId(),
                        candidate.getName(),
                        candidate.getCity(),
                        candidate.getIndustry(),
                        candidate.getReporterUserId(),
                        candidate.getMatchedUscc(),
                        candidate.getStatus(),
                        candidate.getCreatedAt()))
                .toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    @Transactional
    public void matchCandidate(UUID candidateId, String uscc, String note, String ticketId,
                               AdminUser admin, String ip) {
        CompanyCandidate candidate = requirePendingCandidate(candidateId);
        if (uscc == null || !uscc.trim().toUpperCase().matches(USCC_PATTERN)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "USCC 格式不正确");
        }
        String targetUscc = uscc.trim().toUpperCase();
        Company company = companyRepository.findById(targetUscc)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标公司不存在"));
        if (company.getStatus() == CompanyStatus.MERGED) {
            throw new BusinessException(ErrorCode.CONFLICT, "目标公司已合并，请选择主档");
        }
        candidate.setStatus(CandidateStatus.MATCHED);
        candidate.setMatchedUscc(targetUscc);
        candidateRepository.save(candidate);

        auditService.record(admin.getId(), admin.getRole(), "COMPANY_CANDIDATE_MATCH", "COMPANY_CANDIDATE",
                candidateId.toString(), "CANDIDATE_MATCH", detailJson(note, null, targetUscc),
                ticketId, ip, null, null);
    }

    @Transactional
    public String createCompanyFromCandidate(UUID candidateId, CandidateResolveRequest request,
                                             AdminUser admin, String ip) {
        CompanyCandidate candidate = requirePendingCandidate(candidateId);
        BusinessDataProvider.CompanyData providerData = findProviderCompany(candidate, request);
        String uscc;
        String name;
        String shortName;
        String city;
        String industry;
        String companyType;

        if (providerData != null) {
            uscc = providerData.uscc();
            name = providerData.name();
            shortName = providerData.shortName();
            city = providerData.city();
            industry = providerData.industry();
            companyType = providerData.companyType();
        } else {
            String requestedUscc = request == null ? null : request.uscc();
            if (requestedUscc == null || !requestedUscc.trim().toUpperCase().matches(USCC_PATTERN)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "工商数据未匹配到公司，请提供有效 USCC 后再补录");
            }
            uscc = requestedUscc.trim().toUpperCase();
            name = request.name() == null || request.name().isBlank()
                    ? candidate.getName()
                    : request.name().trim();
            shortName = null;
            city = request.city() == null || request.city().isBlank() ? candidate.getCity() : request.city().trim();
            industry = request.industry() == null || request.industry().isBlank()
                    ? candidate.getIndustry()
                    : request.industry().trim();
            companyType = null;
        }

        Optional<Company> existing = companyRepository.findById(uscc);
        Company company;
        if (existing.isPresent()) {
            company = existing.get();
            if (company.getStatus() == CompanyStatus.MERGED) {
                throw new BusinessException(ErrorCode.CONFLICT, "该 USCC 已合并，不能补录");
            }
        } else {
            company = new Company();
            company.setUscc(uscc);
            company.setName(name);
            company.setShortName(blankToNull(shortName));
            company.setCity(blankToNull(city));
            company.setIndustry(blankToNull(industry));
            company.setCompanyType(blankToNull(companyType));
            company.setSource(Source.MANUAL);
            company.setStatus(CompanyStatus.ACTIVE);
            companyRepository.saveAndFlush(company);
        }

        candidate.setStatus(CandidateStatus.CREATED);
        candidate.setMatchedUscc(uscc);
        candidateRepository.save(candidate);

        auditService.record(admin.getId(), admin.getRole(), "COMPANY_CANDIDATE_CREATE", "COMPANY_CANDIDATE",
                candidateId.toString(), "CANDIDATE_CREATE",
                detailJson(request == null ? null : request.note(), name, uscc),
                request == null ? null : request.ticketId(), ip, null, null);
        return uscc;
    }

    @Transactional
    public void rejectCandidate(UUID candidateId, String note, String ticketId, AdminUser admin, String ip) {
        CompanyCandidate candidate = requirePendingCandidate(candidateId);
        candidate.setStatus(CandidateStatus.REJECTED);
        candidateRepository.save(candidate);

        auditService.record(admin.getId(), admin.getRole(), "COMPANY_CANDIDATE_REJECT", "COMPANY_CANDIDATE",
                candidateId.toString(), "CANDIDATE_REJECT", detailJson(note, null, null),
                ticketId, ip, null, null);
    }

    @Transactional
    public void mergeCompanies(String sourceUscc, String targetUscc, String reason, String ticketId,
                               AdminUser admin, String ip) {
        String source = normalizeUscc(sourceUscc);
        String target = normalizeUscc(targetUscc);
        if (source.equals(target)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能将公司合并到自身");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "合并原因不能为空");
        }
        Company sourceCompany = companyRepository.findById(source)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "源公司不存在"));
        Company targetCompany = companyRepository.findById(target)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标公司不存在"));
        if (sourceCompany.getStatus() == CompanyStatus.MERGED) {
            throw new BusinessException(ErrorCode.CONFLICT, "源公司已合并");
        }
        if (targetCompany.getStatus() != CompanyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CONFLICT, "目标公司不是有效档案");
        }

        String beforeHash = hash(companySnapshot(source) + "|" + companySnapshot(target));

        jdbcTemplate.update("UPDATE review SET uscc = ? WHERE uscc = ?", target, source);
        jdbcTemplate.update("UPDATE user_verification SET company_uscc = ? WHERE company_uscc = ?",
                target, source);
        jdbcTemplate.update("UPDATE company_alias SET uscc = ? WHERE uscc = ?", target, source);

        sourceCompany.setStatus(CompanyStatus.MERGED);
        companyRepository.saveAndFlush(sourceCompany);

        jdbcTemplate.update("""
                        INSERT INTO uscc_redirect (old_uscc, new_uscc, reason, operator_id, merged_at)
                        VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (old_uscc) DO UPDATE SET
                            new_uscc = EXCLUDED.new_uscc,
                            reason = EXCLUDED.reason,
                            operator_id = EXCLUDED.operator_id,
                            merged_at = now()
                        """, source, target, reason.trim(), admin.getId());

        aggregateService.recompute(target);
        aggregateService.recompute(source);

        String afterHash = hash(companySnapshot(source) + "|" + companySnapshot(target));
        auditService.record(admin.getId(), admin.getRole(), "COMPANY_MERGE", "COMPANY",
                source, "COMPANY_MERGE",
                detailJson(reason, "合并至 " + target, target), ticketId, ip, beforeHash, afterHash);
    }

    private CompanyCandidate requirePendingCandidate(UUID candidateId) {
        CompanyCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "候选记录不存在"));
        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "该候选已处理");
        }
        return candidate;
    }

    private BusinessDataProvider.CompanyData findProviderCompany(CompanyCandidate candidate,
                                                                 CandidateResolveRequest request) {
        String city = request != null && request.city() != null && !request.city().isBlank()
                ? request.city().trim() : candidate.getCity();
        String industry = request != null && request.industry() != null && !request.industry().isBlank()
                ? request.industry().trim() : candidate.getIndustry();
        String normalizedName = CompanyNameNormalizer.normalize(candidate.getName());
        for (int page = 1; page <= MAX_PROVIDER_PAGES; page++) {
            BusinessDataProvider.CompanyPage companyPage = businessDataProvider.search(
                    city, industry, page, PROVIDER_PAGE_SIZE);
            for (BusinessDataProvider.CompanyData data : companyPage.items()) {
                if (CompanyNameNormalizer.normalize(data.name()).equals(normalizedName)
                        || CompanyNameNormalizer.normalize(data.shortName()).equals(normalizedName)) {
                    return data;
                }
            }
            if ((long) page * PROVIDER_PAGE_SIZE >= companyPage.total() || companyPage.items().isEmpty()) {
                break;
            }
        }
        return null;
    }

    private String normalizeUscc(String uscc) {
        if (uscc == null || !uscc.trim().toUpperCase().matches(USCC_PATTERN)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "USCC 格式不正确");
        }
        return uscc.trim().toUpperCase();
    }

    private String companySnapshot(String uscc) {
        List<String> rows = jdbcTemplate.query("""
                        SELECT uscc, name, short_name, city, industry, company_type, source,
                               review_count, avg_score, score_visible, status
                        FROM company WHERE uscc = ?
                        """,
                (rs, rowNum) -> String.join("|",
                        rs.getString("uscc"),
                        nullSafe(rs.getString("name")),
                        nullSafe(rs.getString("short_name")),
                        nullSafe(rs.getString("city")),
                        nullSafe(rs.getString("industry")),
                        nullSafe(rs.getString("company_type")),
                        nullSafe(rs.getString("source")),
                        String.valueOf(rs.getInt("review_count")),
                        rs.getBigDecimal("avg_score") == null ? "" : rs.getBigDecimal("avg_score").toPlainString(),
                        String.valueOf(rs.getBoolean("score_visible")),
                        nullSafe(rs.getString("status"))),
                uscc);
        return rows.isEmpty() ? uscc + "|MISSING" : rows.get(0);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("审计哈希计算失败");
        }
    }

    private CompanySummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        int reviewCount = rs.getInt("review_count");
        BigDecimal avgScore = rs.getBigDecimal("avg_score");
        Source source;
        try {
            source = Source.valueOf(rs.getString("source"));
        } catch (RuntimeException ex) {
            source = Source.MANUAL;
        }
        return new CompanySummary(
                rs.getString("uscc"),
                rs.getString("name"),
                rs.getString("short_name"),
                rs.getString("city"),
                rs.getString("industry"),
                rs.getString("company_type"),
                source,
                reviewCount,
                avgScore,
                rs.getBoolean("score_visible"),
                reviewCount > 0);
    }

    private String detailJson(String first, String second, String third) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (first != null) {
            detail.put("note", first);
        }
        if (second != null) {
            detail.put("value", second);
        }
        if (third != null) {
            detail.put("uscc", third);
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String reasonCode(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        String trimmed = reason.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
