package com.shipping.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable leases serialize work across browser tabs, schedulers and Cloud Run instances. */
public class VerificationQueue {
    private final JdbcTemplate jdbc;
    public VerificationQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Claim(String emailId, String token) {}
    private TransactionTemplate tx() { return new TransactionTemplate(new JdbcTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))); }
    public Optional<Claim> next() {
        var candidates = jdbc.queryForList("""
            SELECT e.email_id FROM emails e
            LEFT JOIN email_extractions x ON x.email_id=e.email_id
            LEFT JOIN email_assessments a ON a.email_id=e.email_id AND a.revision=x.revision
            LEFT JOIN verification_jobs j ON j.email_id=e.email_id
            WHERE a.email_id IS NULL
              AND (j.lease_until IS NULL OR j.lease_until < CURRENT_TIMESTAMP)
              AND (j.email_id IS NULL OR j.source_revision <> COALESCE(x.revision,'none')
                   OR (j.attempts < 3 AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= CURRENT_TIMESTAMP)))
            ORDER BY e.email_id LIMIT 25
            """, String.class);
        for (String id : candidates) { var claim = claim(id, false); if (claim.isPresent()) return claim; }
        return Optional.empty();
    }
    public Optional<Claim> claim(String id, boolean manual) {
        return tx().execute(status -> {
            if (jdbc.queryForList("SELECT email_id FROM emails WHERE email_id=? FOR UPDATE", String.class, id).isEmpty()) return Optional.empty();
            var revisions = jdbc.queryForList("SELECT revision FROM email_extractions WHERE email_id=?", String.class, id);
            String revision = revisions.isEmpty() ? "none" : revisions.get(0);
            if (!manual && jdbc.queryForObject("SELECT COUNT(*) FROM email_assessments WHERE email_id=? AND revision=?", Integer.class, id, revision) > 0) return Optional.empty();
            var jobs = jdbc.queryForList("SELECT source_revision,lease_until,attempts,next_attempt_at FROM verification_jobs WHERE email_id=?", id);
            int attempts = 0;
            if (!jobs.isEmpty()) {
                var job = jobs.get(0);
                Timestamp lease = (Timestamp)job.get("lease_until");
                if (lease != null && lease.toInstant().isAfter(Instant.now())) return Optional.empty();
                if (revision.equals(job.get("source_revision")) && !manual) {
                    attempts = ((Number)job.get("attempts")).intValue();
                    Timestamp next = (Timestamp)job.get("next_attempt_at");
                    if (attempts >= 3 || (next != null && next.toInstant().isAfter(Instant.now()))) return Optional.empty();
                }
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("DELETE FROM verification_jobs WHERE email_id=?", id);
            jdbc.update("INSERT INTO verification_jobs (email_id,source_revision,lease_token,lease_until,attempts) VALUES (?,?,?,?,?)",
                id, revision, token, Timestamp.from(Instant.now().plusSeconds(600)), attempts + 1);
            return Optional.of(new Claim(id, token));
        });
    }
    public void finish(Claim claim, boolean success) {
        jdbc.update("UPDATE verification_jobs SET lease_token=NULL,lease_until=NULL,next_attempt_at=?,last_error=? WHERE email_id=? AND lease_token=?",
            success ? null : Timestamp.from(Instant.now().plusSeconds(60)), success ? null : "Processing failed. Retry scheduled; after three attempts use Run Verification to retry manually.", claim.emailId(), claim.token());
    }
    public List<Map<String, Object>> failures() {
        return jdbc.queryForList("SELECT email_id, attempts, last_error FROM verification_jobs WHERE last_error IS NOT NULL ORDER BY email_id");
    }
}
