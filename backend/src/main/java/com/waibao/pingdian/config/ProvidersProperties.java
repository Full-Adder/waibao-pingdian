package com.waibao.pingdian.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * providers.mode=mock|real，以及 Mock 参数。
 */
@ConfigurationProperties(prefix = "providers")
public class ProvidersProperties {

    private String mode = "mock";
    private Mock mock = new Mock();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Mock getMock() {
        return mock;
    }

    public void setMock(Mock mock) {
        this.mock = mock;
    }

    public static class Mock {
        private Sms sms = new Sms();
        private Realname realname = new Realname();
        private Ai ai = new Ai();
        private BusinessData businessData = new BusinessData();

        public Sms getSms() {
            return sms;
        }

        public void setSms(Sms sms) {
            this.sms = sms;
        }

        public Realname getRealname() {
            return realname;
        }

        public void setRealname(Realname realname) {
            this.realname = realname;
        }

        public Ai getAi() {
            return ai;
        }

        public void setAi(Ai ai) {
            this.ai = ai;
        }

        public BusinessData getBusinessData() {
            return businessData;
        }

        public void setBusinessData(BusinessData businessData) {
            this.businessData = businessData;
        }
    }

    public static class Sms {
        private String fixedCode = "123456";

        /** 测试用失败注入：仅对时限类通知模板，前 N 次调用抛异常；默认 0 不注入。 */
        private int failFirstAttempts = 0;

        public String getFixedCode() {
            return fixedCode;
        }

        public void setFixedCode(String fixedCode) {
            this.fixedCode = fixedCode;
        }

        public int getFailFirstAttempts() {
            return failFirstAttempts;
        }

        public void setFailFirstAttempts(int failFirstAttempts) {
            this.failFirstAttempts = failFirstAttempts;
        }
    }

    public static class Realname {
        private String failId = "";

        public String getFailId() {
            return failId;
        }

        public void setFailId(String failId) {
            this.failId = failId;
        }
    }

    public static class Ai {
        private double publishConfidence = 0.75;
        private double manualConfidence = 0.50;
        private String rulesVersion = "rules-v1";

        public double getPublishConfidence() {
            return publishConfidence;
        }

        public void setPublishConfidence(double publishConfidence) {
            this.publishConfidence = publishConfidence;
        }

        public double getManualConfidence() {
            return manualConfidence;
        }

        public void setManualConfidence(double manualConfidence) {
            this.manualConfidence = manualConfidence;
        }

        public String getRulesVersion() {
            return rulesVersion;
        }

        public void setRulesVersion(String rulesVersion) {
            this.rulesVersion = rulesVersion;
        }
    }

    public static class BusinessData {
        private int perGroupCount = 417;

        public int getPerGroupCount() {
            return perGroupCount;
        }

        public void setPerGroupCount(int perGroupCount) {
            this.perGroupCount = perGroupCount;
        }
    }
}
