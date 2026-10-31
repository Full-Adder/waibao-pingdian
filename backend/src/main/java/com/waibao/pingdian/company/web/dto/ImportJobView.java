package com.waibao.pingdian.company.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.company.domain.ImportJob;
import com.waibao.pingdian.company.domain.ImportStatus;
import com.waibao.pingdian.company.domain.ImportTriggerType;

import java.time.Instant;
import java.util.UUID;

public record ImportJobView(
        UUID id,
        String city,
        String industry,
        ImportTriggerType triggerType,
        int total,
        int upserted,
        int failed,
        ImportStatus status,
        Instant startedAt,
        Instant finishedAt,
        JsonNode detail
) {
    public static ImportJobView from(ImportJob job) {
        return new ImportJobView(
                job.getId(),
                job.getCity(),
                job.getIndustry(),
                job.getTriggerType(),
                job.getTotal(),
                job.getUpserted(),
                job.getFailed(),
                job.getStatus(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getDetail());
    }
}
