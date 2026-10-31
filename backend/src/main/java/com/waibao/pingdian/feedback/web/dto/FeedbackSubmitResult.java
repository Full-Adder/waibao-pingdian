package com.waibao.pingdian.feedback.web.dto;

import com.waibao.pingdian.feedback.domain.FeedbackStatus;

import java.util.UUID;

public record FeedbackSubmitResult(UUID ticketId, FeedbackStatus status) {
}
