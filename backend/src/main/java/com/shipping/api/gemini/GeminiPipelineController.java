package com.shipping.api.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipping.api.service.EmailDataService;
import com.shipping.api.repository.VerificationQueue;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.List;

@RestController
public class GeminiPipelineController {
    private final DataProcessor processor;
    private final VerificationQueue queue;
    public GeminiPipelineController(EmailDataService emails) { this.processor = new DataProcessor(emails); this.queue = emails.queue(); }
    @PostMapping("/api/gemini/process/{emailId}")
    public JsonNode process(@PathVariable String emailId) {
        var claim = queue.claim(emailId, true).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Email not found or already being processed. Refresh and retry later."));
        return processClaim(claim);
    }
    @PostMapping("/api/gemini/process-next")
    public Map<String, Object> next() {
        var claim = queue.next();
        if (claim.isEmpty()) return Map.of("state", "idle", "message", "No eligible unprocessed emails. Reviews are not reprocessed automatically.");
        try { return Map.of("state", "processed", "emailId", claim.get().emailId(), "result", processClaim(claim.get())); }
        catch (Exception error) { return Map.of("state", "failed", "emailId", claim.get().emailId(), "message", "Processing failed; retry scheduled. Check backend logs or retry manually after three attempts."); }
    }
    @GetMapping("/api/gemini/queue-errors")
    public List<Map<String, Object>> errors() { return queue.failures(); }
    private JsonNode processClaim(VerificationQueue.Claim claim) {
        boolean success = false;
        try { var result = processor.process(claim.emailId()); success = true; return result; }
        finally { queue.finish(claim, success); }
    }
}
