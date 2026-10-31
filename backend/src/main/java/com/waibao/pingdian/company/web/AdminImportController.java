package com.waibao.pingdian.company.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.domain.ImportJob;
import com.waibao.pingdian.company.repo.ImportJobRepository;
import com.waibao.pingdian.company.service.CompanyImportService;
import com.waibao.pingdian.company.service.ImportJobRunner;
import com.waibao.pingdian.company.web.dto.ImportCreateRequest;
import com.waibao.pingdian.company.web.dto.ImportJobView;
import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.MqEventPublisher;
import com.waibao.pingdian.mq.MqProperties;
import com.waibao.pingdian.mq.messages.ImportMessage;
import com.waibao.pingdian.security.CurrentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 导入任务入口。对外路径同时兼容 /api/admin/imports 与契约中的 /api/admin/import-jobs。
 */
@RestController
@RequestMapping({"/api/admin/imports", "/api/admin/import-jobs"})
public class AdminImportController {

    private final CompanyImportService importService;
    private final ImportJobRunner importJobRunner;
    private final ImportJobRepository jobRepository;
    private final CurrentAdminService currentAdminService;
    private final AdminAuditService auditService;
    private final MqEventPublisher eventPublisher;
    private final MqProperties mqProperties;

    public AdminImportController(CompanyImportService importService,
                                 ImportJobRunner importJobRunner,
                                 ImportJobRepository jobRepository,
                                 CurrentAdminService currentAdminService,
                                 AdminAuditService auditService,
                                 MqEventPublisher eventPublisher,
                                 MqProperties mqProperties) {
        this.importService = importService;
        this.importJobRunner = importJobRunner;
        this.jobRepository = jobRepository;
        this.currentAdminService = currentAdminService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.mqProperties = mqProperties;
    }

    @GetMapping
    public ApiResponse<PageResult<ImportJobView>> list(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<ImportJob> result = jobRepository.findAllByOrderByStartedAtDesc(pageable);
        return ApiResponse.ok(PageResult.of(
                result.getContent().stream().map(ImportJobView::from).toList(),
                safePage, safeSize, result.getTotalElements()));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<ImportJobView> detail(@PathVariable UUID jobId) {
        ImportJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "导入任务不存在"));
        return ApiResponse.ok(ImportJobView.from(job));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ImportJobView>> create(@Valid @RequestBody ImportCreateRequest request,
                                                             HttpServletRequest httpRequest) {
        AdminUser admin = currentAdminService.getAdmin();
        ImportJob job = importService.createJob(request.city(), request.industry(), request.triggerType());
        if (mqProperties.isAsync()) {
            // job 保持 RUNNING，由 ImportConsumer 消费执行。
            eventPublisher.publishAfterCommit(RabbitConfig.ROUTING_IMPORT, new ImportMessage(job.getId()));
        } else {
            importJobRunner.run(job.getId());
        }
        auditService.record(admin.getId(), admin.getRole(), "IMPORT_JOB_CREATE", "IMPORT_JOB",
                job.getId().toString(), job.getTriggerType().name(),
                "{\"city\":\"" + job.getCity() + "\",\"industry\":\"" + job.getIndustry() + "\"}",
                null, ClientIpResolver.resolve(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(ImportJobView.from(job)));
    }
}
