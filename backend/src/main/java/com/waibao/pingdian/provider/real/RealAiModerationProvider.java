package com.waibao.pingdian.provider.real;

import com.waibao.pingdian.provider.AiModerationProvider;
import com.waibao.pingdian.provider.ProviderException;

/**
 * 真实 AI 初审供应商占位。
 */
public class RealAiModerationProvider implements AiModerationProvider {

    @Override
    public ModerationResult moderate(ModerationInput input) {
        throw new ProviderException("真实 AI 初审供应商尚未接入");
    }
}
