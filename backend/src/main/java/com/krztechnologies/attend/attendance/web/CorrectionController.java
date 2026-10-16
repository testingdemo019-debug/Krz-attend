package com.krztechnologies.attend.attendance.web;

import com.krztechnologies.attend.attendance.service.CorrectionService;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.crossing.service.CrossingCorrections.Kind;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Corrections of punches (R11): void or flip a crossing, preview the effect on the day, undo from the toast. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class CorrectionController {

    private static final String CODE = "^EV-\\d{6,}$";

    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    /**
     * @param kind {@code VOID} (wrong person) or {@code FLIP} (wrong direction)
     * @param reason the chosen reason, with the note ("Other" is the note alone)
     */
    public record CorrectionRequest(
            @NotNull Kind kind,

            @NotNull
            @Pattern(regexp = "(?s)\\s*\\S.{3,}\\S\\s*", message = "Give a reason of at least 5 characters.")
            @Size(max = 500)
            String reason) {}

    /** @param kind the correction to preview; the reason does not change the result */
    public record PreviewRequest(@NotNull Kind kind) {}

    public record UndoRequest(@NotBlank @Size(max = 64) String undoToken) {}

    @Operation(summary = "Before and after of a correction for the crossing's day (nothing is saved)")
    @RequiresPermission(Permission.ATTENDANCE_EDIT)
    @PostMapping("/crossings/{code}/corrections/preview")
    public CorrectionService.PreviewView preview(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody PreviewRequest request) {
        return corrections.preview(code, request.kind());
    }

    @Operation(summary = "Void a punch or flip its direction; the day is recomputed and HRMS punches follow")
    @RequiresPermission(Permission.ATTENDANCE_EDIT)
    @PostMapping("/crossings/{code}/corrections")
    public CorrectionService.Applied apply(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody CorrectionRequest request) {
        return corrections.apply(code, request.kind(), request.reason().strip());
    }

    @Operation(summary = "Undo a correction within the undo window (only the person who made it)")
    @RequiresPermission(Permission.ATTENDANCE_EDIT)
    @PostMapping("/corrections/undo")
    public ResponseEntity<Void> undo(@Valid @RequestBody UndoRequest request) {
        corrections.undo(request.undoToken());
        return ResponseEntity.noContent().build();
    }
}
