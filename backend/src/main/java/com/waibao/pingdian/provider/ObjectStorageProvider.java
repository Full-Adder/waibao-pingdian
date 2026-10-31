package com.waibao.pingdian.provider;

/**
 * 对象存储：私有加密存储 + 短时签名 URL。
 */
public interface ObjectStorageProvider {

    /**
     * @return 内部 fileKey（非公网地址）
     */
    String put(byte[] bytes, String contentType, String ownerType, String ownerId);

    String signedUrl(String fileKey, int ttlSeconds);

    void delete(String fileKey);
}
