package com.waibao.pingdian.provider;

/**
 * 身份证二要素核验。
 */
public interface RealnameProvider {

    RealnameResult verify(String name, String idNumber);

    record RealnameResult(boolean success, String reasonCode, String message) {

        public static RealnameResult pass() {
            return new RealnameResult(true, null, "核验通过");
        }

        public static RealnameResult fail(String reasonCode, String message) {
            return new RealnameResult(false, reasonCode, message);
        }
    }
}
