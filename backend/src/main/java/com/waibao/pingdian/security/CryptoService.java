package com.waibao.pingdian.security;

import com.waibao.pingdian.common.SensitiveDataMasker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 手机号 / 证件号加解密与唯一性哈希。
 * - AES-256-GCM：密钥来自 CRYPTO_DATA_KEY（32 字节随机串或 Base64），缺失时启动失败；
 * - HMAC-SHA256：密钥来自 CRYPTO_HMAC_KEY，用于 phone_hash 唯一索引。
 * 任何日志与异常都不得输出明文。
 */
@Service
public class CryptoService {

    private static final String PREFIX = "gcm:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec aesKey;
    private final byte[] hmacKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${crypto.data-key:}") String dataKey,
                         @Value("${crypto.hmac-key:}") String hmacKey) {
        this.aesKey = new SecretKeySpec(decodeAesKey(dataKey), "AES");
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalStateException("CRYPTO_HMAC_KEY 未配置：请在 .env 中注入 HMAC 密钥后重启");
        }
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感数据加密失败");
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("密文格式不正确");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感数据解密失败");
        }
    }

    public String hmacSha256Hex(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 计算失败");
        }
    }

    /**
     * 证件号掩码：保留前 6 位与后 4 位。
     */
    public String maskedIdNumber(String idNumber) {
        if (idNumber == null || idNumber.length() < 10) {
            return "***";
        }
        return idNumber.substring(0, 6) + "********" + idNumber.substring(idNumber.length() - 4);
    }

    /**
     * 解密后按手机号掩码返回；解密失败只返回占位符，不抛异常也不输出明文。
     */
    public String maskedPhone(String phoneCipher) {
        try {
            return SensitiveDataMasker.maskPhone(decrypt(phoneCipher));
        } catch (RuntimeException ex) {
            return "***";
        }
    }

    private byte[] decodeAesKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "CRYPTO_DATA_KEY 未配置：请在 .env 中注入 32 字节随机串（openssl rand -base64 32）后重启");
        }
        String value = raw.trim();
        byte[] direct = value.getBytes(StandardCharsets.UTF_8);
        if (direct.length == 32) {
            return direct;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 Base64，按长度错误处理。
        }
        throw new IllegalStateException("CRYPTO_DATA_KEY 长度必须为 32 字节（原始串或 Base64 解码后）");
    }
}
