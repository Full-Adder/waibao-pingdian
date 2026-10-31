package com.waibao.pingdian.company.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.company.domain.ImportJob;
import com.waibao.pingdian.company.domain.ImportStatus;
import com.waibao.pingdian.company.domain.ImportTriggerType;
import com.waibao.pingdian.company.repo.ImportJobRepository;
import com.waibao.pingdian.provider.BusinessDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工商导入：按城市 + 行业分页拉取，按 USCC upsert；单项失败重试最多 3 次。
 * USCC 冲突只更新不新增，重复执行天然幂等。
 */
@Service
public class CompanyImportService {

    private static final Logger log = LoggerFactory.getLogger(CompanyImportService.class);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 500;
    private static final int MAX_ATTEMPTS = 3;

    private final ImportJobRepository jobRepository;
    private final BusinessDataProvider businessDataProvider;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CompanyImportService(ImportJobRepository jobRepository,
                                BusinessDataProvider businessDataProvider,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.businessDataProvider = businessDataProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ImportJob createJob(String city, String industry, String triggerType) {
        ImportJob job = new ImportJob();
        job.setCity(city.trim());
        job.setIndustry(industry.trim());
        job.setTriggerType(parseTriggerType(triggerType));
        job.setStatus(ImportStatus.RUNNING);
        job.setStartedAt(Instant.now());
        return jobRepository.save(job);
    }

    /**
     * 异步执行入口。所有异常都落为 FAILED 并记录失败明细分。
     */
    public void execute(UUID jobId) {
        ImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("import job not found: {}", jobId);
            return;
        }

        int total = 0;
        int upserted = 0;
        int failed = 0;
        int pages = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        String jobError = null;

        try {
            long providerTotal = -1;
            for (int page = 1; page <= MAX_PAGES; page++) {
                BusinessDataProvider.CompanyPage companyPage = businessDataProvider.search(
                        job.getCity(), job.getIndustry(), page, PAGE_SIZE);
                if (providerTotal < 0) {
                    providerTotal = companyPage.total();
                }
                if (companyPage.items().isEmpty()) {
                    break;
                }
                pages = page;
                for (BusinessDataProvider.CompanyData data : companyPage.items()) {
                    total++;
                    if (upsertWithRetry(data, failures)) {
                        upserted++;
                    } else {
                        failed++;
                    }
                }
                persistProgress(job, total, upserted, failed);
                if (providerTotal >= 0 && total >= providerTotal) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            jobError = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            log.warn("import job {} failed: {}", jobId, jobError);
        }

        ImportStatus status;
        if (jobError != null) {
            status = ImportStatus.FAILED;
        } else if (failed == 0) {
            status = ImportStatus.SUCCESS;
        } else if (upserted == 0) {
            status = ImportStatus.FAILED;
        } else {
            status = ImportStatus.PARTIAL;
        }

        job.setTotal(total);
        job.setUpserted(upserted);
        job.setFailed(failed);
        job.setStatus(status);
        job.setFinishedAt(Instant.now());
        job.setDetail(buildDetail(failures, pages, jobError));
        jobRepository.save(job);
    }

    private boolean upsertWithRetry(BusinessDataProvider.CompanyData data,
                                    List<Map<String, Object>> failures) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                jdbcTemplate.update("""
                                INSERT INTO company
                                    (uscc, name, short_name, city, industry, company_type, source, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, 'BUSINESS_API', now())
                                ON CONFLICT (uscc) DO UPDATE SET
                                    name = EXCLUDED.name,
                                    short_name = EXCLUDED.short_name,
                                    city = EXCLUDED.city,
                                    industry = EXCLUDED.industry,
                                    company_type = EXCLUDED.company_type,
                                    source = 'BUSINESS_API',
                                    updated_at = now()
                                """,
                        data.uscc(), data.name(), data.shortName(), data.city(),
                        data.industry(), data.companyType());
                return true;
            } catch (RuntimeException ex) {
                lastError = ex;
            }
        }

        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("uscc", data.uscc());
        failure.put("name", data.name());
        failure.put("attempts", MAX_ATTEMPTS);
        failure.put("error", lastError == null ? "UNKNOWN" : safeMessage(lastError));
        failures.add(failure);
        return false;
    }

    private void persistProgress(ImportJob job, int total, int upserted, int failed) {
        job.setTotal(total);
        job.setUpserted(upserted);
        job.setFailed(failed);
        jobRepository.save(job);
    }

    private ObjectNode buildDetail(List<Map<String, Object>> failures, int pages, String jobError) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("pages", pages);
        ArrayNode failureArray = detail.putArray("failures");
        for (Map<String, Object> failure : failures) {
            ObjectNode item = failureArray.addObject();
            failure.forEach((key, value) -> item.put(key, value == null ? null : value.toString()));
        }
        if (jobError != null) {
            detail.put("error", jobError);
        }
        return detail;
    }

    private ImportTriggerType parseTriggerType(String triggerType) {
        if (triggerType == null || triggerType.isBlank()) {
            return ImportTriggerType.MANUAL;
        }
        try {
            return ImportTriggerType.valueOf(triggerType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "触发类型仅支持 MANUAL / SCHEDULE");
        }
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
