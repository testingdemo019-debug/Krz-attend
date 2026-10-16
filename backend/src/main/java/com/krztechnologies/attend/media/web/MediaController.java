package com.krztechnologies.attend.media.web;

import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.media.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Crossing crops (blurred unless revealed) and the media relay's authorisation check. */
@RestController
@RequestMapping("/api/v1/media")
@Validated
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @Operation(
            summary = "A crossing's face or body crop: blurred, or the original with reveal=true and"
                    + " people.view.sensitive (audited); 410 once purged")
    @RequiresPermission(
            value = {
                Permission.EVENTS_VIEW,
                Permission.REVIEW_RESOLVE,
                Permission.ATTENDANCE_VIEW_ALL,
                Permission.LIVE_VIEW
            },
            anyOf = true)
    @GetMapping("/crops/{crossingCode}/{kind}")
    public ResponseEntity<byte[]> crop(
            @PathVariable @Pattern(regexp = "^EV-\\d{6,}$") String crossingCode,
            @PathVariable @Pattern(regexp = "face|body") String kind,
            @RequestParam(defaultValue = "false") boolean reveal) {
        MediaService.Crop crop =
                media.crop(crossingCode, "face".equals(kind) ? MediaService.Kind.FACE : MediaService.Kind.BODY, reveal);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(crop.mediaType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(kind).build().toString())
                .body(crop.bytes());
    }

    @Operation(
            summary = "nginx auth_request for /media/*: 204 when X-Original-URI is exactly /media/api/ws?src=<enabled"
                    + " camera>, else 403")
    @RequiresPermission(Permission.LIVE_VIEW)
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @RequestHeader(name = "X-Original-URI", required = false) String originalUri) {
        media.authorizeStream(originalUri);
        return ResponseEntity.noContent().build();
    }
}
