package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.repository.ConsentRecords;
import com.krztechnologies.attend.employee.repository.EmployeeRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent to face recognition (R15, R17): the current record of an employee, and every change — given against a
 * notice version with the signed form (stored encrypted by the caller), declined, withdrawn. Earlier records stay as
 * proof.
 */
@Service
public class ConsentService {

    private final ConsentRecords records;
    private final EmployeeRepository employees;
    private final AuditService audit;
    private final TimeService time;

    public ConsentService(ConsentRecords records, EmployeeRepository employees, AuditService audit, TimeService time) {
        this.records = records;
        this.employees = employees;
        this.audit = audit;
        this.time = time;
    }

    /** How consent was obtained (the withdrawal-only {@code WRITTEN_REQUEST} is not offered). */
    public enum Method {
        WRITTEN_FORM("Written (signed form)"),
        EMAIL_CONFIRMATION("E-mail confirmation");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The current consent record. */
    public record Current(String status, String method, Integer noticeVersion, LocalDate givenOn, String recordedBy) {}

    /** The signed form as stored: an encrypted file under {@code uploads/}. */
    public record Document(String path, String mime, String sha256) {}

    @Transactional(readOnly = true)
    public Optional<Current> current(UUID employeeId) {
        return records.current(employeeId)
                .map(c -> new Current(c.status(), c.method(), c.noticeVersion(), c.givenOn(), c.recordedBy()));
    }

    /**
     * Records consent GIVEN for the notice version today.
     *
     * @param document the signed form, or null
     * @return the consent record's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long give(UUID employeeId, Method method, int noticeVersion, Document document, AuditActor actor) {
        String name = employees
                .findById(employeeId)
                .orElseThrow(() -> new Errors.NotFound("Employee not found."))
                .getDisplayName();
        long id = records.record(new ConsentRecords.NewConsent(
                employeeId,
                "GIVEN",
                method.name(),
                noticeVersion,
                actor.employeeId(),
                actor.label(),
                time.now(),
                time.today(),
                document == null ? null : document.path(),
                document == null ? null : document.mime(),
                document == null ? null : document.sha256()));
        audit.record(
                actor,
                AuditCategory.ENROLMENT,
                "Recorded consent",
                name,
                null,
                method.label() + " · notice v" + noticeVersion,
                null);
        return id;
    }

    /** Records that the employee declined enrolment for the notice version (no audit here; the caller audits). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void decline(UUID employeeId, int noticeVersion, AuditActor actor) {
        records.record(new ConsentRecords.NewConsent(
                employeeId,
                "DECLINED",
                null,
                noticeVersion,
                actor.employeeId(),
                actor.label(),
                time.now(),
                null,
                null,
                null,
                null));
    }

    /**
     * Records the withdrawal of a given consent (method, notice version, given date and form stay as proof).
     *
     * @return false when there was no given consent
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean withdraw(UUID employeeId, AuditActor actor) {
        return records.withdraw(employeeId, actor.employeeId(), actor.label(), time.now(), time.today());
    }
}
