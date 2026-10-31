package com.waibao.pingdian.provider.mock;

import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.provider.RealnameProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock 实名核验：18 位身份证校验位 + 姓名长度 ≥ 2。
 * 可通过 providers.mock.realname.fail-id 指定固定失败样例。
 */
public class MockRealnameProvider implements RealnameProvider {

    private static final Logger log = LoggerFactory.getLogger(MockRealnameProvider.class);
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private final ProvidersProperties properties;

    public MockRealnameProvider(ProvidersProperties properties) {
        this.properties = properties;
    }

    @Override
    public RealnameResult verify(String name, String idNumber) {
        String trimmedName = name == null ? "" : name.trim();
        String trimmedId = idNumber == null ? "" : idNumber.trim();

        String forcedFailId = properties.getMock().getRealname().getFailId();
        if (forcedFailId != null && !forcedFailId.isBlank() && forcedFailId.trim().equals(trimmedId)) {
            log.info("[mock-realname] 命中固定失败样例");
            return RealnameResult.fail("MOCK_FORCED_FAIL", "二要素核验未通过");
        }
        if (trimmedName.length() < 2) {
            return RealnameResult.fail("INVALID_NAME", "姓名格式不正确");
        }
        if (!isValidIdNumber(trimmedId)) {
            return RealnameResult.fail("INVALID_ID_NUMBER", "身份证号校验位不正确");
        }
        log.info("[mock-realname] 核验通过");
        return RealnameResult.pass();
    }

    static boolean isValidIdNumber(String id) {
        if (id == null || !id.matches("^\\d{17}[\\dXx]$")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += Character.getNumericValue(id.charAt(i)) * WEIGHTS[i];
        }
        char expected = CHECK_CODES[sum % 11];
        char actual = Character.toUpperCase(id.charAt(17));
        return expected == actual;
    }
}
