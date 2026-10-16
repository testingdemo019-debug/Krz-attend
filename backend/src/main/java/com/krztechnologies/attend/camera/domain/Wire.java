package com.krztechnologies.attend.camera.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The tripwire across the doorway, in normalised image coordinates (0..1, origin top left), stored as
 * {@code {"a":[x,y],"b":[x,y],"inSide":1|-1}}. The arrow points inside: crossing towards {@code inSide} is IN.
 *
 * @param inSide 1 = inside is towards the camera (bottom of the image), -1 = away from it
 */
public record Wire(
        @NotNull @Size(min = 2, max = 2) List<@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double> a,
        @NotNull @Size(min = 2, max = 2) List<@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double> b,
        @NotNull Integer inSide) {

    /** The prototype's starting tripwire for a new camera. */
    public static final Wire DEFAULT = new Wire(List.of(0.06, 0.66), List.of(0.94, 0.66), 1);

    /** The same line with the inside on the other side ("Flip inside direction"). */
    public Wire flipped() {
        return new Wire(a, b, -inSide);
    }
}
