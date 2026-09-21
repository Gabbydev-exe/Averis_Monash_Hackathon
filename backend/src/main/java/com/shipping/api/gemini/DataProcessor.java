package com.shipping.api.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class DataProcessor {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EmailClassifier classifier = new EmailClassifier();
    private final ShipmentExtractor extractor = new ShipmentExtractor();

    private final Path datasetRoot;

    public DataProcessor() {
        String configuredPath = System.getenv("SDOC_DATA_DIR");

        if (configuredPath == null || configuredPath.isBlank()) {
            throw new RuntimeException(
                    "SDOC_DATA_DIR environment variable is not set"
            );
        }

        this.datasetRoot = Path.of(configuredPath);
    }

    public JsonNode process(String emailId) {

        try {
            Path emailFile =
                    datasetRoot.resolve("inbox").resolve(emailId + ".json");

            if (!Files.exists(emailFile)) {
                throw new RuntimeException(
                        "Email file not found: " + emailFile
                );
            }

            JsonNode email = mapper.readTree(emailFile.toFile());

            String actualEmailId =
                    email.path("email_id").asText(emailId);

            String subject =
                    email.path("subject").asText("");

            String body =
                    email.path("body").asText("");

            String category =
                    classifier.classify(actualEmailId, subject, body);

            ObjectNode result = mapper.createObjectNode();

            result.put("email_id", actualEmailId);
            result.put("category", category);
            result.put("subject", subject);

            if (!category.equals("DOCUMENT_COMPARISON")) {
                return result;
            }

            Path attachmentsDirectory =
                    datasetRoot.resolve("attachments");

            Path siFile = findAttachment(
                    attachmentsDirectory,
                    emailId + "_SI"
            );

            Path blFile = findAttachment(
                    attachmentsDirectory,
                    emailId + "_BL"
            );

            if (siFile == null || blFile == null) {

                result.put("status", "REVIEW_REQUIRED");

                if (siFile == null) {
                    result.put("missing_si", true);
                }

                if (blFile == null) {
                    result.put("missing_bl", true);
                }

                return result;
            }

            String siText = readTextAttachment(siFile);
            String blText = readTextAttachment(blFile);

            String siFields = extractor.extract(siText);
            String blFields = extractor.extract(blText);

            result.put("status", "PROCESSED");
            result.set("si", mapper.readTree(siFields));
            result.set("bl", mapper.readTree(blFields));

            return result;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to process email " + emailId +
                            ": " + e.getMessage(),
                    e
            );
        }
    }

    private Path findAttachment(
            Path attachmentsDirectory,
            String prefix
    ) throws IOException {

        if (!Files.exists(attachmentsDirectory)) {
            return null;
        }

        try (Stream<Path> files = Files.walk(attachmentsDirectory)) {

            return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().
                    startsWith(prefix)
            ).findFirst().orElse(null);
        }
    }

    private String readTextAttachment(Path file) throws IOException {

        String filename =
                file.getFileName().toString().toLowerCase();

        if (!filename.endsWith(".txt")) {
            throw new RuntimeException("Unsupported attachment format: " + file);
        }
        return Files.readString(file);
    }
}