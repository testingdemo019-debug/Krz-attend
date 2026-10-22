package com.krztechnologies.attend.enrolment.web;

import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.EnrolmentSettings;
import com.krztechnologies.attend.employee.service.ConsentService;
import com.krztechnologies.attend.enrolment.service.EnrolmentService;
import com.krztechnologies.attend.enrolment.service.EnrolmentViews;
import com.krztechnologies.attend.privacy.service.ConsentNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Face enrolment: the queue, the active consent notice, sessions with consent, captures, duplicate check and save. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class EnrolmentController {

    private static final String CODE = "^ES-\\d{6,}$";

    private final EnrolmentService enrolment;
    private final ConsentNoticeService notices;

    public EnrolmentController(EnrolmentService enrolment, ConsentNoticeService notices) {
        this.enrolment = enrolment;
        this.notices = notices;
    }

    public record StartRequest(
            @NotNull UUID employeeId, @NotNull EnrolmentService.Method method) {}

    /**
     * @param captureIds the accepted captures to keep as templates
     * @param lookalikeConfirmed the operator confirmed the lookalikes are different people
     * @param mode REPLACE (default) or ADD to the active templates
     */
    public record CompleteRequest(
            @NotEmpty @Size(max = 20) List<@NotNull Long> captureIds,
            @Nullable Boolean lookalikeConfirmed,
            EnrolmentService.@Nullable Mode mode) {}

    @Operation(summary = "Employees waiting for enrolment at locations with a camera")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/enrolment/queue")
    public EnrolmentViews.Queue queue() {
        return enrolment.queue();
    }

    @Operation(summary = "Anyone by name or Employee ID, at most 12 (the wizard's search)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/enrolment/people")
    public List<EnrolmentViews.QueueRow> search(@RequestParam @Size(min = 1, max = 100) String q) {
        return enrolment.search(q);
    }

    @Operation(summary = "The chosen person: account, face status, consent, tracking, templates and lockout")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/enrolment/people/{employeeId}")
    public EnrolmentViews.Candidate candidate(@PathVariable UUID employeeId) {
        return enrolment.candidate(employeeId);
    }

    @Operation(summary = "Settings → Enrolment & quality, as the wizard explains and checks them")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/enrolment/rules")
    public EnrolmentSettings rules() {
        return enrolment.rules();
    }

    @Operation(summary = "Test recognition after saving: whom the gallery finds in one camera frame (not stored)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/test")
    public EnrolmentViews.TestResult test(@RequestParam UUID employeeId, @RequestParam MultipartFile image) {
        return enrolment.test(employeeId, bytes(image));
    }

    @Operation(summary = "The consent notice employees consent to now")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/consent-notices/active")
    public ConsentNoticeService.NoticeView activeNotice() {
        return notices.active();
    }

    @Operation(summary = "Start an enrolment session (ends an open one; refused while enrolment is locked)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions")
    public EnrolmentViews.Session start(@Valid @RequestBody StartRequest request) {
        return enrolment.start(request.employeeId(), request.method());
    }

    @Operation(summary = "Record consent for the active notice (GIVEN with an optional signed form, or DECLINED)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/consent")
    public EnrolmentViews.Session consent(
            @PathVariable @Pattern(regexp = CODE) String code,
            @RequestParam @Pattern(regexp = "GIVEN|DECLINED") String decision,
            @RequestParam(required = false) ConsentService.Method method,
            @RequestParam int noticeVersion,
            @RequestParam(required = false) MultipartFile document) {
        return enrolment.consent(
                code,
                "GIVEN".equals(decision),
                method,
                noticeVersion,
                document == null || document.isEmpty() ? null : new EnrolmentService.UploadedDocument(bytes(document)));
    }

    @Operation(summary = "Check one capture or photo with the worker: quality, liveness, accepted or why not")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/captures")
    public EnrolmentViews.CaptureResult capture(
            @PathVariable @Pattern(regexp = CODE) String code,
            @RequestParam MultipartFile image,
            @RequestParam(required = false) @Pattern(regexp = "FRONT|TURN_LEFT|TURN_RIGHT|CHIN_DOWN") String pose) {
        return enrolment.capture(code, bytes(image), pose);
    }

    /**
     * @param cameraCode the camera at the entry point the person walks through
     * @param seconds how long the capture runs unless stopped (10–180, default 90)
     */
    public record CaptureStartRequest(
            @NotNull @Pattern(regexp = "^CAM-\\d{2,}$") String cameraCode,
            @Nullable @Min(10) @Max(180) Integer seconds) {}

    @Operation(summary = "The enabled cameras a capture at an entry point can use")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/enrolment/cameras")
    public List<EnrolmentViews.CaptureCamera> captureCameras() {
        return enrolment.captureCameras();
    }

    @Operation(summary = "The camera's latest picture while capturing at its entry point (never cached)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping(value = "/enrolment/cameras/{cameraCode}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> cameraSnapshot(@PathVariable @Pattern(regexp = "^CAM-\\d{2,}$") String cameraCode) {
        return jpeg(enrolment.cameraSnapshot(cameraCode));
    }

    @Operation(summary = "Start capturing the person's face at the camera of an entry point (method CAMERA)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/capture")
    public EnrolmentViews.CaptureStatus startCapture(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody CaptureStartRequest request) {
        return enrolment.startCapture(code, request.cameraCode(), request.seconds());
    }

    @Operation(summary = "What the capture at the entry point holds so far: angle groups and people in view")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping("/enrolment/sessions/{code}/capture")
    public EnrolmentViews.CaptureStatus captureStatus(@PathVariable @Pattern(regexp = CODE) String code) {
        return enrolment.captureStatus(code);
    }

    @Operation(summary = "Stop the capture at the entry point; its samples become the session's captures")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/capture/stop")
    public EnrolmentViews.Session stopCapture(@PathVariable @Pattern(regexp = CODE) String code) {
        return enrolment.stopCapture(code);
    }

    @Operation(summary = "A capture's image (never cached)")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @GetMapping(value = "/enrolment/sessions/{code}/captures/{captureId}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> captureImage(
            @PathVariable @Pattern(regexp = CODE) String code, @PathVariable long captureId) {
        return jpeg(enrolment.captureImage(code, captureId));
    }

    @Operation(summary = "Remove a capture")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @DeleteMapping("/enrolment/sessions/{code}/captures/{captureId}")
    public EnrolmentViews.Session removeCapture(
            @PathVariable @Pattern(regexp = CODE) String code, @PathVariable long captureId) {
        return enrolment.removeCapture(code, captureId);
    }

    @Operation(summary = "Other enrolled employees whose faces resemble the accepted captures")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/duplicate-check")
    public EnrolmentViews.DuplicateCheck duplicateCheck(@PathVariable @Pattern(regexp = CODE) String code) {
        return enrolment.duplicateCheck(code);
    }

    @Operation(summary = "Save the enrolment from the chosen accepted captures")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/complete")
    public EnrolmentViews.Completed complete(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody CompleteRequest request) {
        return enrolment.complete(
                code,
                request.captureIds(),
                Boolean.TRUE.equals(request.lookalikeConfirmed()),
                request.mode() == null ? EnrolmentService.Mode.REPLACE : request.mode());
    }

    @Operation(summary = "Cancel the session; its captures are discarded")
    @RequiresPermission(Permission.PEOPLE_ENROL)
    @PostMapping("/enrolment/sessions/{code}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable @Pattern(regexp = CODE) String code) {
        enrolment.cancel(code);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Lift a liveness lockout early (System Administrator)")
    @RequiresPermission(Permission.SETTINGS_ROLES)
    @PostMapping("/enrolment/lockouts/{employeeId}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID employeeId) {
        enrolment.unlock(employeeId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<byte[]> jpeg(byte[] image) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(image);
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new Errors.BadRequest("FILE_MISSING", "Attach a file.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
