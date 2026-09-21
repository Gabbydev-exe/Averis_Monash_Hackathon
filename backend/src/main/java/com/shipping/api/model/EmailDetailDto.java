package com.shipping.api.model;

import java.util.List;

public record EmailDetailDto(
        String id,
        String sender,
        String senderName,
        String subject,
        String date,
        String status,
        String bookingNo,
        String vessel,
        String pol,
        String pod,
        String bodyText,
        List<EmailAttachmentDto> attachments,
        List<EmailFieldDto> fields
) {}
