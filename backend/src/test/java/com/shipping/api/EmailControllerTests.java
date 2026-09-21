package com.shipping.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipping.api.controller.EmailController;
import com.shipping.api.model.EmailDetailDto;
import com.shipping.api.model.EmailSummaryDto;
import com.shipping.api.service.EmailDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailControllerTests {

    private EmailDataService emailDataService;
    private EmailController emailController;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        emailDataService = new EmailDataService(objectMapper);
        emailDataService.initialize();
        emailController = new EmailController(emailDataService);
    }

    @Test
    void shouldReturnAllEmailSummaries() {
        List<EmailSummaryDto> summaries = emailController.getEmails();
        assertThat(summaries).isNotEmpty();
        assertThat(summaries.size()).isGreaterThanOrEqualTo(500);

        EmailSummaryDto first = summaries.get(0);
        assertThat(first.id()).isEqualTo("email_001");
        assertThat(first.sender()).isNotBlank();
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.attachmentsCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldReturnEmailDetailForEmail004() {
        ResponseEntity<EmailDetailDto> response = emailController.getEmailById("email_004");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        EmailDetailDto detail = response.getBody();
        assertThat(detail.id()).isEqualTo("email_004");
        assertThat(detail.status()).isEqualTo("pending");
        assertThat(detail.attachments()).hasSize(2);
        assertThat(detail.attachments().get(0).name()).isEqualTo("email_004_SI.txt");
        assertThat(detail.attachments().get(0).type()).isEqualTo("SI");
        assertThat(detail.fields()).hasSize(7);
        assertThat(detail.fields().get(0).label()).isEqualTo("Shipper");
        assertThat(detail.fields().get(0).status()).isEqualTo("pending");
    }

    @Test
    void shouldReturn404ForNonExistentEmail() {
        ResponseEntity<EmailDetailDto> response = emailController.getEmailById("email_non_existent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnAttachmentContent() {
        ResponseEntity<byte[]> response = emailController.getAttachmentContent("email_004", "email_004_SI.txt");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
        String content = new String(response.getBody());
        assertThat(content).contains("SHIPPING INSTRUCTION");
    }

    @Test
    void shouldReturn404ForNonExistentAttachment() {
        ResponseEntity<byte[]> response = emailController.getAttachmentContent("email_004", "non_existent.txt");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
