package com.waibao.pingdian.company.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 公司名称归一化：全半角、空白与末尾括号后缀（如「（集团）」「(分公司)」）。
 */
public final class CompanyNameNormalizer {

    private CompanyNameNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
        value = value.replaceAll("[\\s\\u3000]+", "");
        String previous;
        do {
            previous = value;
            value = value.replaceAll("\\([^()]*\\)$", "")
                    .replaceAll("\\[[^\\[\\]]*\\]$", "")
                    .replaceAll("\\{[^{}]*\\}$", "");
        } while (value.length() != previous.length());
        return value;
    }
}
