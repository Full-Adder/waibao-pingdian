package com.waibao.pingdian.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksPhoneAndIdCardInFreeText() {
        String input = "query phone=13800138000&idNumber=110101199001010015&email=zhangsan@example.com";
        String masked = SensitiveDataMasker.mask(input);

        assertThat(masked)
                .doesNotContain("13800138000")
                .doesNotContain("110101199001010015")
                .doesNotContain("zhangsan@example.com")
                .contains("138****8000")
                .contains("110101********0015");
    }

    @Test
    void masksPhoneAndIdNumberDirectly() {
        assertThat(SensitiveDataMasker.maskPhone("13800138000")).isEqualTo("138****8000");
        assertThat(SensitiveDataMasker.maskIdNumber("110101199001010015"))
                .isEqualTo("110101********0015");
        assertThat(SensitiveDataMasker.maskPhone("123")).isEqualTo("***");
        assertThat(SensitiveDataMasker.maskIdNumber(null)).isEqualTo("***");
    }
}
