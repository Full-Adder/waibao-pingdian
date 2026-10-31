package com.waibao.pingdian.provider.real;

import com.waibao.pingdian.provider.ObjectStorageProvider;
import com.waibao.pingdian.provider.ProviderException;

/**
 * 真实对象存储供应商占位。
 */
public class RealObjectStorageProvider implements ObjectStorageProvider {

    @Override
    public String put(byte[] bytes, String contentType, String ownerType, String ownerId) {
        throw new ProviderException("真实对象存储供应商尚未接入");
    }

    @Override
    public String signedUrl(String fileKey, int ttlSeconds) {
        throw new ProviderException("真实对象存储供应商尚未接入");
    }

    @Override
    public void delete(String fileKey) {
        throw new ProviderException("真实对象存储供应商尚未接入");
    }
}
