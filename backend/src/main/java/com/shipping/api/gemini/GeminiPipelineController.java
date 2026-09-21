package com.shipping.api.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GeminiPipelineController {

    private final DataProcessor processor = new DataProcessor();

    @GetMapping("/api/gemini/process/{emailId}")
    public JsonNode process(@PathVariable String emailId) {
        return processor.process(emailId);
    }
}