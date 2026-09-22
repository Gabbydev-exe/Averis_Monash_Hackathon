package com.shipping.api.gemini;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Vertex {

    private final Client client;
    private static Client sharedClient;
    private static synchronized Client sharedClient() {
        if (sharedClient == null) sharedClient = Client.builder().project("hackathon-509104").location("asia-southeast1").enterprise(true)
                .httpOptions(com.google.genai.types.HttpOptions.builder().timeout(60000)
                        .retryOptions(com.google.genai.types.HttpRetryOptions.builder().attempts(1).build()).build()).build();
        return sharedClient;
    }

    public Vertex() {
        this.client = sharedClient();
    }

    public String generate(String prompt) {

        try {
            GenerateContentResponse response =
                    client.models.generateContent("gemini-2.5-flash", prompt, null);

            String result = response.text();

            if (result == null || result.isBlank()) {
                throw new RuntimeException("Gemini returned an empty response");
            }

            return result.trim();

        } catch (Exception e) {
            throw new RuntimeException("Gemini request failed: " + e.getMessage(), e);
        }
    }

    public String generateJson(String prompt, String schema) {

        try {
            ObjectMapper mapper = new ObjectMapper();

            Object schemaObject =
                    mapper.readValue(schema, Object.class);

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseJsonSchema(schemaObject)
                    .build();

            GenerateContentResponse response =
                    client.models.generateContent(
                            "gemini-2.5-flash",
                            prompt,
                            config
                    );

            String result = response.text();

            if (result == null || result.isBlank()) {
                throw new RuntimeException("Gemini returned an empty response");
            }

            return result.trim();

        } catch (Exception e) {
            throw new RuntimeException("Gemini structured request failed: " + e.getMessage(), e
            );
        }
    }
}