package com.shipping.api.model;

public record EmailSummaryDto(
        String id,
        String sender,
        String senderName,
        String subject,
        String date,
        String status,
        String bookingNo,
        int attachmentsCount,
        String category
) {}
