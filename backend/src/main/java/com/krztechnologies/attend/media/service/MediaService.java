package com.krztechnologies.attend.media.service;

import com.krztechnologies.attend.camera.service.LiveViewService;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.common.media.Images;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.crossing.service.CrossingService;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crossing crops (security §7): the blurred variant by default, the original only on reveal by someone with
 * people.view.sensitive (audited); paths come from the crossing, never from the client. And the check nginx runs before
 * a browser may open a Live View stream through the media relay (security §12).
 */
@Service
public class MediaService {

    /** The only relay path a browser may reach: go2rtc's MSE WebSocket. */
    static final String STREAM_PATH = "/media/api/ws";

    private static final Pattern CAMERA_CODE = Pattern.compile("^CAM-\\d{2,}$");

    private final CrossingService crossings;
    private final LiveViewService liveView;
    private final FileStore files;
    private final CryptoService crypto;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public MediaService(
            CrossingService crossings,
            LiveViewService liveView,
            FileStore files,
            CryptoService crypto,
            CurrentUser currentUser,
            AuditService audit) {
        this.crossings = crossings;
        this.liveView = liveView;
        this.files = files;
        this.crypto = crypto;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** Which crop of a crossing. */
    public enum Kind {
        FACE,
        BODY
    }

    /** A decrypted crop. */
    public record Crop(byte[] bytes, String mediaType) {}

    /**
     * The crop of a crossing: blurred, or the original when {@code reveal} (people.view.sensitive, audited "Revealed
     * crop"). 410 once the retention job purged the crossing's crops; 404 when this crop (or its blurred variant) was
     * never captured.
     */
    @Transactional
    public Crop crop(String crossingCode, Kind kind, boolean reveal) {
        if (reveal && !currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE)) {
            throw new Errors.Forbidden("FORBIDDEN", "Only people who may see sensitive data can reveal crops.");
        }
        CrossingService.StoredCrops crops =
                crossings.crops(crossingCode).orElseThrow(() -> new Errors.NotFound("Crossing not found."));
        if (crops.purged()) {
            throw new Errors.Gone("CROP_PURGED", "This crop was deleted after the retention period.");
        }
        String path = switch (kind) {
            case FACE -> reveal ? crops.face() : crops.faceBlur();
            case BODY -> reveal ? crops.body() : crops.bodyBlur();
        };
        if (path == null) {
            throw new Errors.NotFound(
                    kind == Kind.FACE ? "No face crop for this crossing." : "No body crop for this crossing.");
        }
        byte[] stored = files.read(FileStore.Folder.CROPS, path)
                .orElseThrow(() -> new Errors.NotFound("The crop is no longer stored."));
        byte[] image = crypto.decrypt(stored);
        if (reveal) {
            audit.record(
                    AuditCategory.PRIVACY,
                    "Revealed crop",
                    crossingCode,
                    "Blurred",
                    "Viewed",
                    Map.of("crop", kind == Kind.FACE ? "face" : "body"));
        }
        return new Crop(
                image,
                Images.Format.of(image)
                        .map(Images.Format::contentType)
                        .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
    }

    /**
     * nginx's {@code auth_request} before a Live View stream: the original URI must be exactly
     * {@code /media/api/ws?src=<camera code>} naming an enabled camera. Anything else is refused, so no other relay path
     * (the go2rtc admin API with its stream list) is reachable from a browser.
     */
    @Transactional(readOnly = true)
    public void authorizeStream(String originalUri) {
        String camera = streamCamera(originalUri);
        if (camera == null || !liveView.streamable(camera)) {
            throw new Errors.Forbidden("STREAM_REFUSED", "This stream isn't available.");
        }
    }

    /**
     * The camera code of {@code /media/api/ws?src=<code>}, else null: the path exactly, one query parameter and that
     * one {@code src} with a camera code as is (an encoded or repeated parameter, a fragment or any other parameter is
     * refused rather than interpreted).
     */
    static String streamCamera(String uri) {
        if (uri == null) {
            return null;
        }
        int query = uri.indexOf('?');
        if (query < 0 || !uri.substring(0, query).equals(STREAM_PATH)) {
            return null;
        }
        String parameters = uri.substring(query + 1);
        if (!parameters.startsWith("src=")) {
            return null;
        }
        String code = parameters.substring("src=".length());
        return CAMERA_CODE.matcher(code).matches() ? code : null;
    }
}
