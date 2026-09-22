package com.shipping.api.document;

public interface AttachmentObjectStore {
    String put(byte[] bytes);
    byte[] read(String objectName);
    void delete(String objectName);
}
