package com.shipping.api;

import com.shipping.api.repository.EmailRepository;
import com.shipping.api.service.EmailDataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

final class DatabaseFixtures {
    static void workflow(JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("db/persistence.sql"), new ClassPathResource("db/automation.sql"), new ClassPathResource("db/gcs.sql")).execute(jdbc.getDataSource());
    }
    static void schema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE emails (email_id VARCHAR(64) PRIMARY KEY, sender_address VARCHAR(320) NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL, raw_email CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE attachments (attachment_id VARCHAR(64) PRIMARY KEY, email_id VARCHAR(64) REFERENCES emails(email_id), source_path VARCHAR(512), attachment_order INT, file_name VARCHAR(255), mime_type VARCHAR(128), byte_size BIGINT, sha256 VARCHAR(64))");
        workflow(jdbc);
    }
    static void seedBundle(EmailDataService service) throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var resource : resolver.getResources("classpath:data/bundle/inbox/*.json")) {
            byte[] bytes = resource.getContentAsByteArray();
            service.importJson(bytes);
            var email = mapper.readTree(bytes);
            for (var ref : email.path("attachments")) {
                var attachment = new ClassPathResource("data/bundle/" + ref.asText());
                if (attachment.exists()) service.saveAttachment(email.path("email_id").asText(), ref.asText().substring(ref.asText().lastIndexOf('/') + 1), attachment.getContentAsByteArray());
            }
        }
    }
}
