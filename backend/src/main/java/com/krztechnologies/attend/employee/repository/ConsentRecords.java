package com.krztechnologies.attend.employee.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes to the consent ledger. A change never overwrites a record: the current one is retired
 * ({@code is_current = false}) and a new current one is added, so the given consent stays as proof.
 */
@Repository
public class ConsentRecords {

    private final JdbcClient jdbc;

    public ConsentRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new current consent record; the previous one is retired. */
    public record NewConsent(
            UUID employeeId,
            String status,
            String method,
            int noticeVersion,
            UUID recordedBy,
            String recordedByLabel,
            Instant at,
            LocalDate givenOn,
            String documentPath,
            String documentMime,
            String documentSha256) {}

    /** Retires the current record and adds this one as current; returns its id. */
    public long record(NewConsent c) {
        jdbc.sql("UPDATE attend.consent SET is_current = false WHERE employee_id = :id AND is_current")
                .param("id", c.employeeId())
                .update();
        return jdbc.sql("""
                        INSERT INTO attend.consent (employee_id, status, method, notice_version, recorded_by,
                                                    recorded_by_label, recorded_at, given_on, document_path,
                                                    document_mime, document_sha256, is_current)
                        VALUES (:employee, :status, :method, :notice, :by, :byLabel, :at, :given, :path, :mime, :sha, true)
                        RETURNING id
                        """)
                .param("employee", c.employeeId())
                .param("status", c.status())
                .param("method", c.method())
                .param("notice", c.noticeVersion())
                .param("by", c.recordedBy())
                .param("byLabel", c.recordedByLabel())
                .param("at", Timestamp.from(c.at()))
                .param("given", c.givenOn())
                .param("path", c.documentPath())
                .param("mime", c.documentMime())
                .param("sha", c.documentSha256())
                .query(Long.class)
                .single();
    }

    /** The current record's status, method label key and notice version, if there is one. */
    public Optional<Current> current(UUID employeeId) {
        return jdbc.sql("""
                        SELECT id, status, method, notice_version, given_on, recorded_by_label
                          FROM attend.consent WHERE employee_id = :id AND is_current
                        """)
                .param("id", employeeId)
                .query((rs, n) -> new Current(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("method"),
                        (Integer) rs.getObject("notice_version"),
                        rs.getObject("given_on", LocalDate.class),
                        rs.getString("recorded_by_label")))
                .optional();
    }

    /** The current consent record. */
    public record Current(
            long id, String status, String method, Integer noticeVersion, LocalDate givenOn, String recordedBy) {}

    /**
     * Records that a given consent was withdrawn (method, notice version, given date and signed form are kept).
     *
     * @return false when there was no given consent to withdraw
     */
    public boolean withdraw(UUID employeeId, UUID actorId, String actorLabel, Instant at, LocalDate day) {
        Long current = jdbc.sql("""
                        UPDATE attend.consent SET is_current = false
                         WHERE employee_id = :id AND is_current AND status = 'GIVEN'
                        RETURNING id
                        """)
                .param("id", employeeId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (current == null) {
            return false;
        }
        jdbc.sql("""
                        INSERT INTO attend.consent (employee_id, status, method, notice_version, recorded_by,
                                                    recorded_by_label, recorded_at, given_on, withdrawn_on,
                                                    document_path, document_mime, document_sha256, is_current)
                        SELECT employee_id, 'WITHDRAWN', method, notice_version, :actorId, :actorLabel, :at, given_on,
                               :day, document_path, document_mime, document_sha256, true
                          FROM attend.consent WHERE id = :previous
                        """)
                .param("actorId", actorId)
                .param("actorLabel", actorLabel)
                .param("at", Timestamp.from(at))
                .param("day", day)
                .param("previous", current)
                .update();
        return true;
    }
}
