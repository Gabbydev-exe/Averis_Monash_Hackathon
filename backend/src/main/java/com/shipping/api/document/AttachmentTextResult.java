package com.shipping.api.document;

public record AttachmentTextResult(
        String filename,
        AttachmentReadStatus status,
        String text,
        String message
) {
}
