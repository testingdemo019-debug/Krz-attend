package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.common.media.Images;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisation logo (Settings → General): PNG or JPEG only (never SVG, which can carry scripts), at most 512 KB,
 * checked by its magic bytes, decoded (dimensions checked before decoding, so a small file cannot expand into a huge
 * image) and re-encoded server-side, which strips all metadata (security §7). Stored as {@code uploads/branding/logo};
 * served publicly by {@code GET /public/logo} with an ETag.
 */
@Service
public class LogoService {

    static final int MAX_BYTES = 512 * 1024;
    static final int MIN_SIDE = 16;
    static final int MAX_SIDE = 2048;
    private static final String FILE = "branding/logo";
    private static final float JPEG_QUALITY = 0.92f;

    private final FileStore files;
    private final AuditService audit;
    /** The stored logo, read once from disk; null until first needed and after every change. */
    private final AtomicReference<Optional<Logo>> current = new AtomicReference<>();

    public LogoService(FileStore files, AuditService audit) {
        this.files = files;
        this.audit = audit;
    }

    /**
     * The stored logo.
     *
     * @param contentType {@code image/png} or {@code image/jpeg}
     * @param etag a hash of the content (also the cache-busting {@code v} of {@link #url()})
     */
    public record Logo(byte[] content, String contentType, String etag) {}

    /** What the settings page shows after an upload. */
    public record LogoView(String logoUrl, String contentType, int width, int height, int bytes) {}

    public Optional<Logo> current() {
        Optional<Logo> logo = current.get();
        if (logo == null) {
            logo = files.read(FileStore.Folder.UPLOADS, FILE).flatMap(LogoService::describe);
            current.set(logo);
        }
        return logo;
    }

    /** The URL the UI loads the logo from, or null when none was uploaded (the UI then shows the "KRZ" mark). */
    public String url() {
        return current().map(logo -> "/api/v1/public/logo?v=" + logo.etag()).orElse(null);
    }

    @Transactional
    public LogoView upload(byte[] upload) {
        if (upload.length > MAX_BYTES) {
            throw new Errors.BusinessRule("LOGO_TOO_LARGE", "The logo must be 512 KB or smaller.");
        }
        Images.Format format = Images.Format.of(upload)
                .orElseThrow(() -> new Errors.BusinessRule(
                        "LOGO_TYPE", "The logo must be a PNG or JPEG image. SVG and other formats are not accepted."));
        BufferedImage image;
        byte[] reEncoded;
        try {
            image = Images.decode(upload, format, MIN_SIDE, MAX_SIDE);
            reEncoded = Images.encode(image, format, JPEG_QUALITY);
        } catch (Images.ImageException e) {
            throw e.problem() == Images.Problem.SIZE
                    ? new Errors.BusinessRule(
                            "LOGO_SIZE",
                            "The logo must be between " + MIN_SIDE + " and " + MAX_SIDE + " pixels wide and high.")
                    : unreadable();
        }
        boolean replaced = current().isPresent();
        String summary =
                format.name() + " · " + image.getWidth() + " × " + image.getHeight() + " px · " + kb(reEncoded.length);
        audit.record(
                AuditCategory.SETTINGS,
                replaced ? "Changed logo" : "Uploaded logo",
                "General",
                replaced ? "Logo" : "No logo",
                summary,
                Map.of("bytes", reEncoded.length));
        files.write(FileStore.Folder.UPLOADS, FILE, reEncoded);
        current.set(null);
        return new LogoView(url(), format.contentType(), image.getWidth(), image.getHeight(), reEncoded.length);
    }

    /** Removes the logo (the UI falls back to the "KRZ" text mark); nothing happens when there is none. */
    @Transactional
    public void remove() {
        if (current().isEmpty()) {
            return;
        }
        audit.record(AuditCategory.SETTINGS, "Removed logo", "General", "Logo", "No logo", null);
        files.delete(FileStore.Folder.UPLOADS, FILE);
        current.set(null);
    }

    private static Optional<Logo> describe(byte[] content) {
        return Images.Format.of(content).map(format -> new Logo(content, format.contentType(), sha256(content)));
    }

    private static Errors.BusinessRule unreadable() {
        return new Errors.BusinessRule("LOGO_UNREADABLE", "The file could not be read as a PNG or JPEG image.");
    }

    private static String kb(int bytes) {
        return Math.max(1, Math.round(bytes / 1024f)) + " KB";
    }

    /** The first 16 hex digits of the SHA-256: enough to tell logo versions apart. */
    private static String sha256(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
