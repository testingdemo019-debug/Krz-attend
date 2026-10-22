package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.camera.service.CameraCoverage;
import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.domain.Employee;
import com.krztechnologies.attend.employee.domain.Employment.TrackingMode;
import com.krztechnologies.attend.employee.domain.Employment.TrackingReason;
import com.krztechnologies.attend.employee.domain.FaceProfile;
import com.krztechnologies.attend.employee.domain.FaceProfileHistory;
import com.krztechnologies.attend.employee.domain.FaceStatus;
import com.krztechnologies.attend.employee.repository.EmployeeQueries;
import com.krztechnologies.attend.employee.repository.EmployeeRepository;
import com.krztechnologies.attend.employee.repository.EmploymentRepository;
import com.krztechnologies.attend.employee.repository.FaceProfileHistoryRepository;
import com.krztechnologies.attend.employee.repository.FaceProfileRepository;
import com.krztechnologies.attend.privacy.service.BiometricErasureService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Face profile actions of the employee profile (R17): deactivate (stop matching, keep templates), reactivate, and
 * erase biometric data. Each one is recorded in the profile history, audited, and tells the worker to reload the
 * gallery ({@code krz:config gallery}) after the commit. Also serves the two sensitive files of the profile.
 */
@Service
public class FaceProfileService {

    private static final String OTHER = "Other";
    private static final String CONSENT_WITHDRAWN = "Consent withdrawn";

    /** The prototype's reasons for deactivating; "Other" needs a note. */
    public static final List<String> DEACTIVATE_REASONS =
            List.of("Long leave", "Appearance changed — re-enrolment planned", "Requested by the employee", OTHER);

    /** The prototype's reasons for erasing; "Other" needs a note. */
    public static final List<String> ERASE_REASONS =
            List.of(CONSENT_WITHDRAWN, "Left the organisation", "Privacy request", OTHER);

    private final EmployeeRepository employees;
    private final EmploymentRepository employments;
    private final FaceProfileRepository profiles;
    private final FaceProfileHistoryRepository history;
    private final EmployeeQueries queries;
    private final ConsentService consents;
    private final BiometricErasureService erasure;
    private final CameraCoverage cameras;
    private final FileStore files;
    private final CryptoService crypto;
    private final ConfigPublisher config;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final ApplicationEventPublisher events;

    public FaceProfileService(
            EmployeeRepository employees,
            EmploymentRepository employments,
            FaceProfileRepository profiles,
            FaceProfileHistoryRepository history,
            EmployeeQueries queries,
            ConsentService consents,
            BiometricErasureService erasure,
            CameraCoverage cameras,
            FileStore files,
            CryptoService crypto,
            ConfigPublisher config,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            ApplicationEventPublisher events) {
        this.employees = employees;
        this.employments = employments;
        this.profiles = profiles;
        this.history = history;
        this.queries = queries;
        this.consents = consents;
        this.erasure = erasure;
        this.cameras = cameras;
        this.files = files;
        this.crypto = crypto;
        this.config = config;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.events = events;
    }

    /** A stored file with its media type and the name it downloads as. */
    public record FileContent(byte[] bytes, String mediaType, String fileName) {}

    /** Where a template's thumbnail is served (people.view.sensitive). */
    public static String thumbnailUrl(UUID employeeId, long templateId) {
        return "/api/v1/employees/" + employeeId + "/templates/" + templateId + "/thumbnail";
    }

    /**
     * The signed consent form's name, as the profile shows it and the download saves it: "consent-EMP-1002.pdf"
     * (the prototype's file name), "consent-form.pdf" while the Employee ID is missing.
     */
    public static String consentFileName(@Nullable String employeeId, String mediaType) {
        String extension = switch (mediaType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> "";
        };
        return "consent-" + (employeeId == null ? "form" : employeeId) + extension;
    }

    /**
     * Stops matching the employee; the templates stay. Tracking becomes Not tracked — No face profile, so HR records
     * attendance in KRZ HRMS meanwhile.
     */
    @Transactional
    public void deactivate(UUID id, String reason, String note) {
        Employee person = person(id);
        FaceProfile profile = profiles.findById(id)
                .filter(p -> p.getStatus().isActive())
                .orElseThrow(() -> new Errors.BusinessRule("NO_ACTIVE_PROFILE", "No active face profile."));
        String detail = detail(DEACTIVATE_REASONS, reason, note);
        String before = profile.getStatus().label();
        profile.deactivate(detail);
        employments.findById(id).ifPresent(e -> e.setTracking(TrackingMode.NOT_TRACKED, TrackingReason.NO_FACE));
        AttendPrincipal actor = currentUser.require();
        history.save(new FaceProfileHistory(
                id,
                time.now(),
                FaceProfileHistory.Action.DEACTIVATED,
                actor.employeeId(),
                actor.displayName(),
                detail));
        audit.record(
                AuditCategory.EMPLOYEES,
                "Deactivated face profile",
                person.getDisplayName(),
                before,
                "Deactivated — " + detail,
                null);
        trackingChanged(id);
        config.publishGalleryAfterCommit(id);
    }

    /**
     * Matches the employee again with the kept templates. Needs an enabled account, templates and given consent.
     * Tracking returns to Tracked by camera when it was off only for the missing face profile and the location has a
     * camera.
     */
    @Transactional
    public void reactivate(UUID id) {
        Employee person = person(id);
        FaceProfile profile = profiles.findById(id)
                .filter(p -> p.getStatus() == FaceStatus.DEACTIVATED)
                .orElseThrow(() -> new Errors.BusinessRule("NOT_DEACTIVATED", "The face profile is not deactivated."));
        boolean consentGiven =
                queries.consent(id).map(c -> "GIVEN".equals(c.status())).orElse(false);
        if (!person.isAccountEnabled() || queries.activeTemplateCount(id) == 0 || !consentGiven) {
            throw new Errors.BusinessRule("CANNOT_REACTIVATE", "Needs an enabled account, templates and consent.");
        }
        profile.reactivate();
        employments
                .findById(id)
                .filter(e -> e.getTrackingReason() == TrackingReason.NO_FACE)
                .filter(e -> e.getLocationId() != null && cameras.locationHasCamera(e.getLocationId()))
                .ifPresent(e -> e.setTracking(TrackingMode.TRACKED, null));
        AttendPrincipal actor = currentUser.require();
        history.save(new FaceProfileHistory(
                id, time.now(), FaceProfileHistory.Action.REACTIVATED, actor.employeeId(), actor.displayName(), null));
        audit.record(
                AuditCategory.EMPLOYEES,
                "Reactivated face profile",
                person.getDisplayName(),
                FaceStatus.DEACTIVATED.label(),
                FaceStatus.ENROLLED.label(),
                null);
        trackingChanged(id);
        config.publishGalleryAfterCommit(id);
    }

    /**
     * Erases templates, enrolment images and crops (R17; the typed confirmation is checked by the API). "Consent
     * withdrawn" also records the withdrawal. Attendance history stays.
     */
    @Transactional
    public BiometricErasureService.ErasureResult erase(UUID id, String reason, String note) {
        Employee person = person(id);
        int templates = queries.activeTemplateCount(id);
        if (templates == 0) {
            throw new Errors.BusinessRule("NOTHING_TO_ERASE", "Nothing to erase.");
        }
        String detail = detail(ERASE_REASONS, reason, note);
        AttendPrincipal principal = currentUser.require();
        AuditActor actor = AuditActor.employee(principal.employeeId(), principal.displayName());
        if (CONSENT_WITHDRAWN.equals(reason)) {
            consents.withdraw(id, actor);
        }
        var result = erasure.erase(id, detail, actor);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", detail);
        details.put("templates", result.templates());
        details.put("captures", result.captures());
        details.put("crossingsWithCrops", result.crossings());
        details.put("files", result.files());
        audit.record(
                AuditCategory.PRIVACY,
                "Erased biometric data",
                person.getDisplayName(),
                templates == 1 ? "1 template" : templates + " templates",
                FaceStatus.ERASED.label(),
                details);
        return result;
    }

    /**
     * R15 save: the profile is enrolled (re-enrolled when it had templates or was flagged), the history and the audit
     * say so, tracking switches to Tracked by camera when it was off only for the missing face profile and the location
     * has a camera, and the worker reloads the gallery.
     *
     * @param templatesBefore active templates before this enrolment
     * @param total active templates now
     * @param detail the history detail ("5 templates · live camera · liveness passed")
     * @return whether tracking changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enrolled(UUID employeeId, int templatesBefore, int total, String detail, AuditActor actor) {
        Employee person = person(employeeId);
        FaceProfile profile = profiles.findById(employeeId).orElseGet(() -> new FaceProfile(employeeId));
        boolean first = templatesBefore == 0 && profile.getStatus() != FaceStatus.NEEDS_REENROL;
        String before = profile.getStatus().label() + " (" + templatesBefore + ")";
        profile.enrol(time.now());
        profiles.save(profile);
        history.save(new FaceProfileHistory(
                employeeId,
                time.now(),
                first ? FaceProfileHistory.Action.ENROLLED : FaceProfileHistory.Action.REENROLLED,
                actor.employeeId(),
                actor.label(),
                detail));
        audit.record(
                actor,
                AuditCategory.ENROLMENT,
                first ? "Enrolled face" : "Re-enrolled face",
                person.getDisplayName(),
                before,
                "Enrolled (" + total + ")",
                null);
        boolean trackingChanged = employments
                .findById(employeeId)
                .filter(e -> e.getTrackingMode() == TrackingMode.NOT_TRACKED
                        && e.getTrackingReason() == TrackingReason.NO_FACE)
                .filter(e -> e.getLocationId() != null && cameras.locationHasCamera(e.getLocationId()))
                .map(e -> {
                    String was = e.trackingLabel();
                    e.setTracking(TrackingMode.TRACKED, null);
                    audit.record(
                            actor,
                            AuditCategory.EMPLOYEES,
                            "Changed attendance tracking",
                            person.getDisplayName(),
                            was,
                            e.trackingLabel(),
                            null);
                    return true;
                })
                .orElse(false);
        trackingChanged(employeeId);
        config.publishGalleryAfterCommit(employeeId);
        return trackingChanged;
    }

    /**
     * The employee declined enrolment (R15): consent DECLINED for the notice, not tracked (no face profile), the
     * history and the audit say so.
     *
     * @param consentBefore the consent status before ("Given", "None", …) for the audit
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void declined(UUID employeeId, int noticeVersion, String consentBefore, AuditActor actor) {
        Employee person = person(employeeId);
        consents.decline(employeeId, noticeVersion, actor);
        FaceProfile profile = profiles.findById(employeeId).orElseGet(() -> new FaceProfile(employeeId));
        profile.declined();
        profiles.save(profile);
        employments
                .findById(employeeId)
                .ifPresent(e -> e.setTracking(TrackingMode.NOT_TRACKED, TrackingReason.NO_FACE));
        history.save(new FaceProfileHistory(
                employeeId,
                time.now(),
                FaceProfileHistory.Action.ENROLMENT_DECLINED,
                actor.employeeId(),
                actor.label(),
                "HR records attendance in KRZ HRMS"));
        audit.record(
                actor,
                AuditCategory.ENROLMENT,
                "Recorded that employee declined enrolment",
                person.getDisplayName(),
                consentBefore,
                "Declined",
                null);
        trackingChanged(employeeId);
        config.publishGalleryAfterCommit(employeeId);
    }

    /** A crop from the Review Queue became a template (R12): the profile's history says so. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void templateAddedFromReview(UUID employeeId, String reviewCode, AuditActor actor) {
        history.save(new FaceProfileHistory(
                employeeId,
                time.now(),
                FaceProfileHistory.Action.TEMPLATE_ADDED_FROM_REVIEW,
                actor.employeeId(),
                actor.label(),
                reviewCode));
    }

    /** A crossing's face crop became a template (D-118). */
    public void templateAddedFromCrossing(UUID employeeId, String crossingCode, AuditActor actor) {
        history.save(new FaceProfileHistory(
                employeeId,
                time.now(),
                FaceProfileHistory.Action.TEMPLATE_ADDED_FROM_CROSSING,
                actor.employeeId(),
                actor.label(),
                crossingCode));
    }

    /** Tracking may have changed: today is recomputed. */
    private void trackingChanged(UUID id) {
        profiles.flush();
        employments.flush();
        events.publishEvent(AttendanceAffected.employees(List.of(id), time.today(), time.today()));
    }

    /** The signed consent form (people.view.sensitive). */
    @Transactional(readOnly = true)
    public FileContent consentDocument(UUID id) {
        person(id);
        var stored = queries.consentDocument(id).orElseThrow(() -> new Errors.NotFound("No consent document."));
        String employeeId =
                employments.findById(id).map(e -> e.getHrmsEmployeeId()).orElse(null);
        return read(stored, consentFileName(employeeId, stored.mime()));
    }

    /** A template's thumbnail (people.view.sensitive). */
    @Transactional(readOnly = true)
    public FileContent thumbnail(UUID id, long templateId) {
        var stored = queries.thumbnail(id, templateId).orElseThrow(() -> new Errors.NotFound("No thumbnail."));
        return read(stored, "template");
    }

    /** Consent documents and thumbnails are stored encrypted (security §6). */
    private FileContent read(EmployeeQueries.StoredFile stored, String fileName) {
        byte[] bytes = files.read(FileStore.Folder.UPLOADS, stored.path())
                .orElseThrow(() -> new Errors.NotFound("The file is no longer stored."));
        return new FileContent(crypto.decrypt(bytes), stored.mime(), fileName);
    }

    private Employee person(UUID id) {
        return employees.findById(id).orElseThrow(() -> new Errors.NotFound("Employee not found."));
    }

    /** The reason as recorded: the chosen one, or the note for "Other". */
    private static String detail(List<String> allowed, String reason, String note) {
        if (reason == null || !allowed.contains(reason)) {
            throw invalid("reason", "Choose a reason.");
        }
        if (!OTHER.equals(reason)) {
            return reason;
        }
        if (note == null || note.isBlank()) {
            throw invalid("note", "A note is required for Other.");
        }
        return note.trim();
    }

    private static Errors.BusinessRule invalid(String field, String message) {
        return new Errors.BusinessRule("VALIDATION", message, List.of(new ErrorResponse.FieldError(field, message)));
    }
}
