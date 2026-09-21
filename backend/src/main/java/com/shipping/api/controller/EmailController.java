package com.shipping.api.controller;

import com.shipping.api.model.EmailDetailDto;
import com.shipping.api.model.EmailSummaryDto;
import com.shipping.api.service.EmailDataService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLConnection;
import java.util.List;

@RestController
@RequestMapping({"/api/emails", "/api/emails/"})
@CrossOrigin(origins = "*")
public class EmailController {

    private final EmailDataService emailDataService;

    public EmailController(EmailDataService emailDataService) {
        this.emailDataService = emailDataService;
    }

    @GetMapping({"", "/"})
    public List<EmailSummaryDto> getEmails() {
        return emailDataService.getAllEmailSummaries();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailDetailDto> getEmailById(@PathVariable String id) {
        return emailDataService.getEmailDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/{id}/attachments/{filename}")
    public ResponseEntity<byte[]> getAttachmentContent(
            @PathVariable String id,
            @PathVariable String filename
    ) {
        return emailDataService.getAttachmentContent(filename)
                .map(bytes -> {
                    String contentType = URLConnection.guessContentTypeFromName(filename);
                    if (contentType == null) {
                        if (filename.endsWith(".txt")) {
                            contentType = "text/plain; charset=utf-8";
                        } else if (filename.endsWith(".pdf")) {
                            contentType = "application/pdf";
                        } else if (filename.endsWith(".docx")) {
                            contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                        } else if (filename.endsWith(".xlsx")) {
                            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                        } else {
                            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
                        }
                    }

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                            .body(bytes);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
