package com.waibao.pingdian.provider.mock;

import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.provider.AiModerationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mock AI 初审规则引擎（rules-v1）。
 * 覆盖五类：隐私红线、广告联系方式、人身攻击、虚构违法事实、AI 模板化痕迹；
 * 另检测「具体指控」关键词（拖欠工资、克扣、押金、培训费、不交社保、强制加班等）。
 */
public class MockAiModerationProvider implements AiModerationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockAiModerationProvider.class);

    private static final Pattern ID_NUMBER = Pattern.compile("(?<![\\dXx])\\d{17}[\\dXx](?![\\dXx])");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern AD_CONTACT = Pattern.compile(
            "(?i)(加|联系|私聊|扫码).{0,8}(微信|wechat|vx|v信|qq|企微)|(微信|wechat|vx|v信|qq)\\s*[:：号]?\\s*[a-z0-9_-]{4,}|加我|内推找我|联系方式");
    private static final Pattern PERSONAL_ATTACK = Pattern.compile(
            "黑心|骗子|狗公司|垃圾公司|无良|傻逼|脑残|畜生|去死|滚蛋|人渣|奸商");
    private static final Pattern ILLEGAL_FACT = Pattern.compile(
            "偷税漏税|逃税|漏税|吸毒|诈骗|洗钱|非法集资|行贿|传销|走私|非法经营");
    private static final Pattern SPECIFIC_ALLEGATION = Pattern.compile(
            "拖欠工资|拖欠薪资|克扣|押金|培训费|不交社保|未交社保|未缴社保|强制加班|加班费|扣押|罚款|扣工资");
    private static final Pattern AI_TEMPLATE = Pattern.compile(
            "综上所述|总而言之|总的来说|首先|其次|最后|此外|值得一提的是|不得不说|不难发现|由此可见");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?；;\\n]");

    private final ProvidersProperties properties;

    public MockAiModerationProvider(ProvidersProperties properties) {
        this.properties = properties;
    }

    @Override
    public ModerationResult moderate(ModerationInput input) {
        String content = input == null || input.content() == null ? "" : input.content();
        boolean hasEvidence = input != null && input.hasEvidence();

        List<String> flags = new ArrayList<>();
        if (ID_NUMBER.matcher(content).find() || PHONE.matcher(content).find()) {
            flags.add("PRIVACY_REDLINE");
        }
        if (AD_CONTACT.matcher(content).find()) {
            flags.add("AD_CONTACT");
        }
        if (PERSONAL_ATTACK.matcher(content).find()) {
            flags.add("PERSONAL_ATTACK");
        }
        if (ILLEGAL_FACT.matcher(content).find()) {
            flags.add("FABRICATED_VIOLATION");
        }
        boolean specificAllegation = SPECIFIC_ALLEGATION.matcher(content).find();
        if (specificAllegation) {
            flags.add("SPECIFIC_ALLEGATION");
        }
        if (looksAiGenerated(content)) {
            flags.add("AI_GENERATED");
        }

        boolean redline = flags.contains("PRIVACY_REDLINE")
                || flags.contains("AD_CONTACT")
                || flags.contains("PERSONAL_ATTACK");

        double confidence;
        SuggestedStatus suggested;
        if (redline) {
            confidence = 0.95;
            suggested = SuggestedStatus.REJECTED;
        } else if (flags.contains("FABRICATED_VIOLATION") || flags.contains("AI_GENERATED")) {
            double base = 0.62 - (flags.size() - 1) * 0.05;
            confidence = clamp(base);
            suggested = SuggestedStatus.MANUAL;
        } else if (specificAllegation && !hasEvidence) {
            confidence = 0.55;
            suggested = SuggestedStatus.MANUAL;
        } else {
            double base = 0.92 - Math.max(0, flags.size() - 1) * 0.12;
            if (specificAllegation) {
                base -= 0.10;
            }
            confidence = clamp(base);
            suggested = confidence >= properties.getMock().getAi().getPublishConfidence()
                    ? SuggestedStatus.PUBLISHED
                    : SuggestedStatus.MANUAL;
        }

        log.info("[mock-ai] flags={} confidence={} suggested={}", flags, confidence, suggested);
        return new ModerationResult(List.copyOf(new LinkedHashSet<>(flags)), confidence,
                properties.getMock().getAi().getRulesVersion(), suggested);
    }

    private static double clamp(double value) {
        double bounded = Math.max(0.05, Math.min(0.99, value));
        return Math.round(bounded * 1000.0) / 1000.0;
    }

    /**
     * 模板化痕迹：句长方差过低 + 模板词 ≥ 2，且正文足够长。
     */
    static boolean looksAiGenerated(String content) {
        if (content == null || content.length() < 60) {
            return false;
        }
        Set<String> templates = new LinkedHashSet<>();
        var matcher = AI_TEMPLATE.matcher(content);
        while (matcher.find()) {
            templates.add(matcher.group());
        }
        double variance = sentenceLengthVariance(content);
        return templates.size() >= 2 && variance < 12.0;
    }

    private static double sentenceLengthVariance(String content) {
        String[] sentences = SENTENCE_SPLIT.split(content);
        List<Integer> lengths = new ArrayList<>();
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.length() >= 4) {
                lengths.add(trimmed.length());
            }
        }
        if (lengths.size() < 3) {
            return Double.MAX_VALUE;
        }
        double mean = lengths.stream().mapToInt(Integer::intValue).average().orElse(0);
        double sum = 0;
        for (int length : lengths) {
            sum += (length - mean) * (length - mean);
        }
        return sum / lengths.size();
    }
}
