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

    @GetMapping("/export-results")
    public ResponseEntity<byte[]> exportAllResults(@RequestParam(defaultValue="json") String format) {
        return exportResultSelection(format, new ExportSelection(emailDataService.getAllEmailSummaries().stream().map(EmailSummaryDto::id).toList()));
    }

    @PostMapping("/export-results")
    public ResponseEntity<byte[]> exportResultSelection(@RequestParam(defaultValue="json") String format, @RequestBody ExportSelection selection) {
        if (selection.emailIds() == null || selection.emailIds().isEmpty() || selection.emailIds().size() > 10000
                || selection.emailIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,64}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select between 1 and 10,000 valid email IDs.");
        var result = new java.util.LinkedHashMap<String, Object>();
        for (String id : new java.util.LinkedHashSet<>(selection.emailIds())) {
            var workflow = emailDataService.workflow(id);
            var assessment = workflow.assessment();
            if (assessment == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email " + id + " has not been processed since its last update. Finish processing before exporting results.");
            var record = new java.util.LinkedHashMap<String, Object>();
            record.put("category", workflow.category());
            record.put("status", assessment.status());
            record.put("review_reason", assessment.reviewReason());
            record.put("defect_fields", assessment.defectFields());
            record.put("has_defect", assessment.status().equals("MISMATCH"));
            result.put(id, record);
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            byte[] bytes;
            if (format.equals("json")) bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
            else if (format.equals("csv")) {
                var csv = new StringBuilder("\uFEFFemail_id,category,status,review_reason,defect_fields,has_defect\r\n");
                for (var entry : result.entrySet()) {
                    var row = mapper.valueToTree(entry.getValue());
                    var cells = List.of(entry.getKey(), row.path("category").asText(), row.path("status").asText(), row.path("review_reason").isNull() ? "" : row.path("review_reason").asText(), row.path("defect_fields").toString(), row.path("has_defect").asText());
                    csv.append(cells.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(","))).append("\r\n");
                }
                bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
            } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export format must be json or csv.");
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"submission." + format + "\"")
                .contentType(format.equals("json") ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv;charset=utf-8")).body(bytes);
        } catch (IOException e) { throw new IllegalStateException("Could not serialize results", e); }
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

    @GetMapping("/{id}/workflow")
    public com.shipping.api.repository.EmailWorkflow.Snapshot workflow(@PathVariable String id) {
        return emailDataService.workflow(id);
    }

    @PutMapping("/{id}/extraction")
    public com.shipping.api.repository.EmailWorkflow.Snapshot saveExtraction(@PathVariable String id,
            @RequestBody com.shipping.api.repository.EmailWorkflow.Extraction input) {
        return emailDataService.saveExtraction(id, input);
    }

    @PostMapping("/{id}/reviews")
    public com.shipping.api.repository.EmailWorkflow.Snapshot saveReview(@PathVariable String id,
            @RequestBody com.shipping.api.repository.EmailWorkflow.Review input) {
        return emailDataService.saveReview(id, input);
    }

    @PostMapping(value = "/{id}/attachments/{filename}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> uploadAttachment(@PathVariable String id, @PathVariable String filename,
                                                HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(EmailJsonData.MAX_BYTES + 1);
        if (bytes.length > EmailJsonData.MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment must be at most 5 MB.");
        emailDataService.uploadAttachment(id, filename, bytes);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(value = "/{id}/attachments/{filename}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> saveAttachment(@PathVariable String id, @PathVariable String filename,
                                              HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > EmailJsonData.MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment must be at most 5 MB.");
        byte[] bytes = request.getInputStream().readNBytes(EmailJsonData.MAX_BYTES + 1);
        if (bytes.length > EmailJsonData.MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment must be at most 5 MB.");
        emailDataService.saveAttachment(id, filename, bytes);
        return ResponseEntity.noContent().build();
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
                            .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.inline().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                            .header("X-Content-Type-Options", "nosniff")
                            .body(bytes);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseUnavailable(DataAccessException exception) {
        Throwable root = exception.getMostSpecificCause();
        log.warn("Email database query failed ({}): {}", exception.getClass().getSimpleName(), root.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("status", "unavailable", "message", "Email database is unavailable. Please retry."));
    }
}
