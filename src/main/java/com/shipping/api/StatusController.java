package com.shipping.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    @GetMapping({"/", "/api/status"})
    public Map<String, String> status() {
        return Map.of(
                "service", "shipping-api",
                "status", "ok",
                "message", "Spring Boot backend is running");
    }
}
