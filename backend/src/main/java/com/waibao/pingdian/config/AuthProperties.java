package com.waibao.pingdian.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private Code code = new Code();
    private Register register = new Register();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Code getCode() {
        return code;
    }

    public void setCode(Code code) {
        this.code = code;
    }

    public Register getRegister() {
        return register;
    }

    public void setRegister(Register register) {
        this.register = register;
    }

    public static class Code {
        private int ttlMinutes = 5;
        private int resendSeconds = 60;

        public int getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(int ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }

        public int getResendSeconds() {
            return resendSeconds;
        }

        public void setResendSeconds(int resendSeconds) {
            this.resendSeconds = resendSeconds;
        }
    }

    public static class Register {
        private int ipDailyLimit = 3;

        public int getIpDailyLimit() {
            return ipDailyLimit;
        }

        public void setIpDailyLimit(int ipDailyLimit) {
            this.ipDailyLimit = ipDailyLimit;
        }
    }

    public static class Jwt {
        private String secret = "";
        private int userTtlMinutes = 10080;
        private int adminTtlMinutes = 480;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getUserTtlMinutes() {
            return userTtlMinutes;
        }

        public void setUserTtlMinutes(int userTtlMinutes) {
            this.userTtlMinutes = userTtlMinutes;
        }

        public int getAdminTtlMinutes() {
            return adminTtlMinutes;
        }

        public void setAdminTtlMinutes(int adminTtlMinutes) {
            this.adminTtlMinutes = adminTtlMinutes;
        }
    }
}
