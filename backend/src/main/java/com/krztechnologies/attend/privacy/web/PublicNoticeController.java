package com.krztechnologies.attend.privacy.web;

import com.krztechnologies.attend.common.security.PublicEndpoint;
import com.krztechnologies.attend.privacy.service.ConsentNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The face recognition notice for everyone, before sign-in (the sign-in screen's "Privacy notice" link, D-086): only
 * its version, text and publication date, not who published it.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicNoticeController {

    private final ConsentNoticeService notices;

    public PublicNoticeController(ConsentNoticeService notices) {
        this.notices = notices;
    }

    public record PublicNotice(int version, String text, Instant publishedAt) {}

    @Operation(summary = "The active face recognition notice (sign-in screen)")
    @PublicEndpoint
    @GetMapping("/privacy-notice")
    public PublicNotice privacyNotice() {
        var notice = notices.active();
        return new PublicNotice(notice.version(), notice.text(), notice.publishedAt());
    }
}
