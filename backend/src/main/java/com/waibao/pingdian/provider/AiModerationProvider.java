package com.waibao.pingdian.provider;

import java.util.List;

/**
 * AI 初审：输出命中项、置信度、规则版本与建议流转。
 */
public interface AiModerationProvider {

    ModerationResult moderate(ModerationInput input);

    record ModerationInput(String content, int totalScore, boolean hasEvidence, boolean copyDetected) {
    }

    record ModerationResult(
            List<String> flags,
            double confidence,
            String rulesVersion,
            SuggestedStatus suggestedStatus
    ) {
    }

    enum SuggestedStatus {
        PUBLISHED,
        MANUAL,
        REJECTED
    }
}
