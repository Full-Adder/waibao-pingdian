package com.waibao.pingdian.company.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waibao.pingdian.company.domain.ImportJob;
import com.waibao.pingdian.company.domain.ImportStatus;
import com.waibao.pingdian.company.repo.ImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入任务执行入口：MQ 消费者与同步模式都调用无 @Async 的 runJob；
 * 同步模式通过 importExecutor 异步调度，保持接口立即返回 RUNNING。
 */
@Component
public class ImportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRunner.class);

    private final CompanyImportService importService;
    private final ImportJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public ImportJobRunner(CompanyImportService importService,
                           ImportJobRepository jobRepository,
                           ObjectMapper objectMapper) {
        this.importService = importService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行：任务已完成（非 RUNNING）时直接跳过，重复消息不会重复导入。
     */
    public void runJob(UUID jobId) {
        ImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("import job not found: {}", jobId);
            return;
        }
        if (job.getStatus() != ImportStatus.RUNNING) {
            log.info("import job already finished, skip: jobId={} status={}", jobId, job.getStatus());
            return;
        }
        importService.execute(jobId);
    }

    @Async("importExecutor")
    public void run(UUID jobId) {
        runJob(jobId);
    }

    /**
     * MQ 重试耗尽后的兜底：仅 RUNNING 任务置 FAILED，避免覆盖已落库的最终结果。
     */
    @Transactional
    public void markFailed(UUID jobId, String error) {
        ImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("import job not found on failure: {}", jobId);
            return;
        }
        if (job.getStatus() != ImportStatus.RUNNING) {
            return;
        }
        job.setStatus(ImportStatus.FAILED);
        job.setFinishedAt(Instant.now());
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("error", error == null ? "UNKNOWN" : error);
        job.setDetail(detail);
        jobRepository.save(job);
        log.warn("import job marked FAILED after retries: jobId={}", jobId);
    }
}
