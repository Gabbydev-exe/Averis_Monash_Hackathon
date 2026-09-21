package com.shipping.api.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipping.api.service.EmailDataService;
import org.springframework.web.bind.annotation.*;

@RestController
public class GeminiPipelineController {
    private final DataProcessor processor;
    public GeminiPipelineController(EmailDataService emails) { this.processor = new DataProcessor(emails); }
    @PostMapping("/api/gemini/process/{emailId}")
    public JsonNode process(@PathVariable String emailId) { return processor.process(emailId); }
}
