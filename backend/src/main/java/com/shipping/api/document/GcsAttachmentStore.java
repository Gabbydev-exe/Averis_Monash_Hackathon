package com.shipping.api.document;

import com.google.cloud.storage.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GcsAttachmentStore implements AttachmentObjectStore {
    private final String bucket;
    private Storage storage;
    public GcsAttachmentStore(@Value("${attachments.gcs.bucket:}") String bucket) { this.bucket = bucket; }
    private synchronized Storage client() {
        if (bucket.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Set ATTACHMENTS_GCS_BUCKET before uploading attachments.");
        if (storage == null) storage = StorageOptions.getDefaultInstance().getService();
        return storage;
    }
    public String put(byte[] bytes) {
        try {
            String name = "attachments/" + java.util.UUID.randomUUID();
            client().create(BlobInfo.newBuilder(bucket, name).setContentType("application/octet-stream").build(), bytes, Storage.BlobTargetOption.doesNotExist());
            return name;
        } catch (StorageException e) { throw unavailable(); }
    }
    public byte[] read(String name) {
        if (!name.startsWith("attachments/")) throw unavailable();
        try { return client().readAllBytes(bucket, name); }
        catch (StorageException e) { throw unavailable(); }
    }
    public void delete(String name) {
        if (!name.startsWith("attachments/")) throw unavailable();
        try { client().delete(bucket, name); } catch (StorageException e) { throw unavailable(); }
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloud attachment storage unavailable. Check bucket configuration and IAM permissions.");
    }
}
