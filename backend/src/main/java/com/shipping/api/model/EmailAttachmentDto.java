package com.shipping.api.model;

public record EmailAttachmentDto(
        String name,
        String path,
        String type,
        String size
) {}
