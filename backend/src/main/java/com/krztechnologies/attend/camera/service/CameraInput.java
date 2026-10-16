package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.rules.service.CameraMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The editable details and connection of a camera (Settings → Cameras editor), as added and as saved. The password
 * is set separately and geometry has its own endpoint.
 *
 * @param entryPoint the entry point the camera watches (EP-nn)
 * @param model the camera model (free text, e.g. CP-UNC-DA41L3C-D-LQ2)
 * @param host the camera's IPv4 address
 * @param mainPath RTSP path of the main stream (recognition, worker)
 * @param subPath RTSP path of the sub stream (Live View, go2rtc)
 * @param username a view-only camera user, never the camera admin
 * @param enabled processing on or off
 * @param version the version the edit started from (required when saving; ignored when adding)
 */
public record CameraInput(
        @NotBlank(message = "Enter a camera name") @Size(min = 2, max = 80)
        String name,

        @NotNull @Pattern(regexp = "^EP-\\d{2,}$") String entryPoint,
        @NotNull CameraMode directionMode,
        @Nullable @Size(max = 300) String mountNote,
        @NotNull FacesVisibleWhen facesVisibleWhen,
        @Nullable @Size(max = 120) String model,

        @NotNull @Pattern(regexp = IPV4, message = "Enter a valid IP address")
        String host,

        @NotNull @Min(1) @Max(65535) Integer rtspPort,
        @NotNull @Min(1) @Max(65535) Integer onvifPort,

        @NotNull @Pattern(regexp = RTSP_PATH, message = "Start with / and use no spaces")
        String mainPath,

        @NotNull @Pattern(regexp = RTSP_PATH, message = "Start with / and use no spaces")
        String subPath,

        @Nullable @Size(max = 120) @Pattern(regexp = "^\\S*$", message = "Use no spaces")
        String username,

        @NotNull Boolean enabled,
        @Nullable Integer version) {

    public static final String IPV4 =
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$";
    public static final String RTSP_PATH = "^/\\S{0,299}$";

    public CameraInput {
        name = name == null ? null : name.trim();
        mountNote = blankToNull(mountNote);
        model = blankToNull(model);
        username = blankToNull(username);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
