package com.waibao.pingdian.complaint.web.dto;

import java.time.Instant;

public record ComplaintBlocklistRequest(String uscc, String reason, String evidenceFile, Instant until) {
}
