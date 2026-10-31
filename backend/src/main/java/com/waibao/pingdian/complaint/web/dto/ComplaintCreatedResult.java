package com.waibao.pingdian.complaint.web.dto;

import com.waibao.pingdian.complaint.domain.ComplaintStatus;

import java.util.UUID;

public record ComplaintCreatedResult(UUID complaintId, ComplaintStatus status) {
}
