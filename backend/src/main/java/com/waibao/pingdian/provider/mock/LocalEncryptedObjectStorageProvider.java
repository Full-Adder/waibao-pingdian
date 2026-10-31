package com.waibao.pingdian.provider.mock;

import com.waibao.pingdian.config.StorageProperties;
import com.waibao.pingdian.provider.ObjectStorageProvider;
import com.waibao.pingdian.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * 本地私有对象存储（AES-GCM 信封加密）：
 * 每个文件随机 DEK，DEK 用主密钥包裹；签名 URL 默认 5 分钟有效。
 * 主密钥只来自 STORAGE_MASTER_KEY，不在代码内写死。
 */
public class LocalEncryptedObjectStorageProvider implements ObjectStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalEncryptedObjectStorageProvider.class);

    private static final byte[] MAGIC = {'W', 'B', 'P', 'D'};
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int DEK_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final int MIN_TTL_SECONDS = 30;
    private static final int MAX_TTL_SECONDS = 3600;
    private static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("yyyy/MM");

    private final StorageProperties properties;
    private final Path root;
    private byte[] cachedMasterKey;

    public LocalEncryptedObjectStorageProvider(StorageProperties properties) {
        this.properties = properties;
        this.root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
    }

    @Override
    public String put(byte[] bytes, String contentType, String ownerType, String ownerId) {
        if (bytes == null) {
            throw new ProviderException("文件内容为空");
        }
        byte[] masterKey = masterKey();
        String fileKey = "obj/" + LocalDate.now(ZoneOffset.UTC).format(PATH_DATE) + "/" + UUID.randomUUID() + ".bin";
        try {
            byte[] dek = randomBytes(DEK_BYTES);
            byte[] contentIv = randomBytes(IV_BYTES);
            byte[] wrapIv = randomBytes(IV_BYTES);

            Cipher contentCipher = Cipher.getInstance("AES/GCM/NoPadding");
            contentCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, contentIv));
            byte[] ciphertext = contentCipher.doFinal(bytes);

            Cipher wrapCipher = Cipher.getInstance("AES/GCM/NoPadding");
            wrapCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, wrapIv));
            byte[] wrappedDek = wrapCipher.doFinal(dek);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.write(MAGIC);
                out.writeInt(VERSION);
                out.write(contentIv);
                out.write(wrapIv);
                out.writeInt(wrappedDek.length);
                out.write(wrappedDek);
                out.write(ciphertext);
            }

            Path target = resolveSafe(fileKey);
            Files.createDirectories(target.getParent());
            Files.write(target, buffer.toByteArray(), StandardOpenOption.CREATE_NEW);
            log.info("[storage] put fileKey={} ownerType={} ownerId={} contentType={} size={}",
                    fileKey, ownerType, ownerId, contentType, bytes.length);
            return fileKey;
        } catch (Exception ex) {
            throw new ProviderException("文件加密写入失败", ex);
        }
    }

    @Override
    public String signedUrl(String fileKey, int ttlSeconds) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new ProviderException("fileKey 不能为空");
        }
        int ttl = ttlSeconds <= 0 ? properties.getDefaultTtlSeconds() : ttlSeconds;
        ttl = Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, ttl));
        long expires = Instant.now().getEpochSecond() + ttl;
        String signature = hmacBase64Url(fileKey + "|" + expires);
        String encodedKey = java.util.Arrays.stream(fileKey.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "/" + b)
                .orElse("");
        return "/api/materials/" + encodedKey + "?expires=" + expires + "&sig=" + signature;
    }

    @Override
    public void delete(String fileKey) {
        try {
            Files.deleteIfExists(resolveSafe(fileKey));
            log.info("[storage] delete fileKey={}", fileKey);
        } catch (IOException ex) {
            throw new ProviderException("文件删除失败", ex);
        }
    }

    private Path resolveSafe(String fileKey) {
        if (fileKey == null || fileKey.isBlank() || fileKey.contains("..")) {
            throw new ProviderException("非法 fileKey");
        }
        Path target = root.resolve(fileKey).normalize();
        if (!target.startsWith(root)) {
            throw new ProviderException("非法 fileKey");
        }
        return target;
    }

    private synchronized byte[] masterKey() {
        if (cachedMasterKey != null) {
            return cachedMasterKey;
        }
        String configured = properties.getMasterKey();
        if (configured == null || configured.isBlank()) {
            throw new ProviderException("STORAGE_MASTER_KEY 未配置，无法使用本地加密存储");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new ProviderException("STORAGE_MASTER_KEY 必须是 Base64 编码", ex);
        }
        if (decoded.length != DEK_BYTES && decoded.length != 16 && decoded.length != 24) {
            throw new ProviderException("STORAGE_MASTER_KEY 长度必须为 16/24/32 字节");
        }
        cachedMasterKey = decoded;
        return cachedMasterKey;
    }

    private String hmacBase64Url(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey(), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new ProviderException("签名生成失败", ex);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(bytes);
        return bytes;
    }
}
