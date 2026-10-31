package com.waibao.pingdian.feedback.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeedbackReplyRequest(
        @NotBlank @Size(min = 5, max = 1000) String responseNote,
        Boolean close
) {
}
