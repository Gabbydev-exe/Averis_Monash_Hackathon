package com.shipping.api.controller;

import com.shipping.api.model.EmailDetailDto;
import com.shipping.api.model.EmailSummaryDto;
import com.shipping.api.document.AttachmentTextResult;
import com.shipping.api.service.EmailDataService;
import com.shipping.api.service.EmailJsonData;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLConnection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/emails", "/api/emails/"})
@CrossOrigin(origins = "*")
public class EmailController {

    private static final Logger log = LoggerFactory.getLogger(EmailController.class);

    private final EmailDataService emailDataService;

    public EmailController(EmailDataService emailDataService) {
        this.emailDataService = emailDataService;
    }

    @GetMapping({"", "/"})
    public List<EmailSummaryDto> getEmails() {
        return emailDataService.getAllEmailSummaries();
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EmailDataService.ImportResult importEmails(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > EmailJsonData.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "JSON data must be at most 5 MB.");
        }
        return emailDataService.importJson(request.getInputStream().readNBytes(EmailJsonData.MAX_BYTES + 1));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEmails(@RequestParam(defaultValue = "json") String format) {
        return exportResponse(format, emailDataService.exportSourceEmails());
    }

    public record ExportSelection(List<String> emailIds) {}

    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportSelectedEmails(@RequestParam(defaultValue = "json") String format,
                                                       @RequestBody ExportSelection selection) {
        if (selection.emailIds() == null || selection.emailIds().isEmpty()
                || selection.emailIds().size() > 10000
                || selection.emailIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select between 1 and 10,000 valid email IDs.");
        }
        var ids = new java.util.HashSet<>(selection.emailIds());
        var records = emailDataService.exportSourceEmails().stream()
                .filter(record -> ids.contains(record.path("email_id").asText())).toList();
        if (records.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Some selected emails are no longer available. Refresh the list and select again.");
        }
        return exportResponse(format, records);
    }

    private ResponseEntity<byte[]> exportResponse(String format, List<com.fasterxml.jackson.databind.JsonNode> records) {
        byte[] data;
        MediaType contentType;
        if (format.equals("json")) {
            try { data = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(records); }
            catch (IOException exception) { throw new IllegalStateException("Could not serialize email data", exception); }
            contentType = MediaType.APPLICATION_JSON;
        } else if (format.equals("csv")) {
            data = EmailJsonData.csv(records).getBytes(StandardCharsets.UTF_8);
            contentType = MediaType.parseMediaType("text/csv; charset=utf-8");
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export format must be json or csv.");
        return ResponseEntity.ok().contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shipping-emails." + format + "\"")
                .body(data);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalidImport(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", java.util.Objects.requireNonNullElse(exception.getReason(), "Invalid request.")));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailDetailDto> getEmailById(@PathVariable String id) {
        return emailDataService.getEmailDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/{id}/attachments/{filename}/text")
    public ResponseEntity<AttachmentTextResult> getAttachmentText(
            @PathVariable String id,
            @PathVariable String filename
    ) {
        return emailDataService.getAttachmentText(id, filename)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/{id}/attachments/{filename}")
    public ResponseEntity<byte[]> getAttachmentContent(
            @PathVariable String id,
            @PathVariable String filename
    ) {
        return emailDataService.getAttachmentContent(id, filename)
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

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseUnavailable(DataAccessException exception) {
        log.warn("Email database query failed ({})", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("status", "unavailable", "message", "Email database is unavailable. Please retry."));
    }
}
