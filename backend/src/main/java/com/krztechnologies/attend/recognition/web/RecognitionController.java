package com.krztechnologies.attend.recognition.web;

import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.EnrolmentSettings;
import com.krztechnologies.attend.recognition.domain.ThresholdValues;
import com.krztechnologies.attend.recognition.service.RecognitionService;
import com.krztechnologies.attend.recognition.service.RecognitionViews;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settings → Recognition (thresholds, profiles, liveness, impact preview) and Settings → Enrolment &amp; quality. */
@RestController
@RequestMapping("/api/v1/settings")
@Validated
public class RecognitionController {

    private static final String PROFILE_CODE = "^TP-\\d+$";

    private final RecognitionService recognition;

    public RecognitionController(RecognitionService recognition) {
        this.recognition = recognition;
    }

    /**
     * @param note why the values changed (default "Edited in Settings")
     * @param basedOn the code of the active profile the edit started from
     */
    public record SaveThresholdsRequest(
            @NotNull @Valid ThresholdValues values,
            @Size(max = 300) String note,
            @NotNull @Pattern(regexp = PROFILE_CODE) String basedOn) {}

    public record LivenessRequest(@NotNull Boolean enabled) {}

    public record UpdateEnrolmentRequest(
            @NotNull @Valid EnrolmentSettings settings,
            @NotNull Integer version) {}

    @Operation(summary = "Active thresholds, threshold profiles, per-camera liveness and the worker's models")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @GetMapping("/recognition")
    public RecognitionViews.RecognitionView get() {
        return recognition.view();
    }

    @Operation(summary = "Save thresholds as a new profile TP-n and use it")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @PutMapping("/recognition")
    public RecognitionViews.RecognitionView save(@Valid @RequestBody SaveThresholdsRequest request) {
        recognition.save(request.values(), request.note(), request.basedOn());
        return recognition.view();
    }

    @Operation(summary = "Switch back to an earlier threshold profile")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @PostMapping("/recognition/profiles/{code}/activate")
    public RecognitionViews.RecognitionView activate(@PathVariable @Pattern(regexp = PROFILE_CODE) String code) {
        recognition.activate(code);
        return recognition.view();
    }

    @Operation(summary = "Turn the passive liveness check of one camera on or off")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @PutMapping("/recognition/liveness/{cameraCode}")
    public CameraViews.CameraLiveness setLiveness(
            @PathVariable @Pattern(regexp = "^CAM-\\d{2,}$") String cameraCode,
            @Valid @RequestBody LivenessRequest request) {
        return recognition.setLiveness(cameraCode, request.enabled());
    }

    @Operation(
            summary = "Impact preview: the last 14 days of face scores split by these thresholds and the active ones")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @GetMapping("/recognition/impact")
    public RecognitionViews.ImpactView impact(
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("1.0") Double accept,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("1.0") Double reviewBand,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("1.0") Double margin) {
        return recognition.impact(accept, reviewBand, margin);
    }

    @Operation(summary = "Enrolment & quality settings")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @GetMapping("/enrolment")
    public RecognitionViews.EnrolmentView enrolment() {
        return recognition.enrolment();
    }

    @Operation(summary = "Save Enrolment & quality (send the version you edited)")
    @RequiresPermission(Permission.SETTINGS_RECOGNITION)
    @PutMapping("/enrolment")
    public RecognitionViews.EnrolmentView updateEnrolment(@Valid @RequestBody UpdateEnrolmentRequest request) {
        return recognition.updateEnrolment(request.settings(), request.version());
    }
}
