package com.krztechnologies.attend.enrolment.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.FaceProfileService;
import com.krztechnologies.attend.enrolment.repository.TemplateStore;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Face templates: from accepted enrolment captures (R15; live, upload, or a capture at an entry point, D-118), from a
 * crop of the Review Queue (R12, source REVIEW) whose undo removes it again, and from the face crop of an identified
 * crossing (source CROSSING, D-118). Embeddings are stored encrypted and never leave the backend; the capture's or
 * crop's encrypted image becomes the template's thumbnail. Which crops may become templates is decided here once, for
 * the Review Queue and for crossings alike (the prototype's templateEligibility).
 */
@Service
public class TemplateService {

    /** A crop needs at least this sharpness (0–100) to become a template. */
    static final int MIN_SHARPNESS = 60;
    /** A crop's head turn or tilt (degrees) may be at most this for a template. */
    static final int MAX_POSE_DEGREES = 20;

    private static final String THUMBNAILS = "templates/";

    private final TemplateStore store;
    private final FileStore files;
    private final CryptoService crypto;
    private final WorkerClient worker;
    private final FaceProfileService faces;
    private final EmployeeDirectory employees;
    private final SettingsService settings;
    private final ConfigPublisher config;
    private final JsonMapper json;

    public TemplateService(
            TemplateStore store,
            FileStore files,
            CryptoService crypto,
            WorkerClient worker,
            FaceProfileService faces,
            EmployeeDirectory employees,
            SettingsService settings,
            ConfigPublisher config,
            JsonMapper json) {
        this.store = store;
        this.files = files;
        this.crypto = crypto;
        this.worker = worker;
        this.faces = faces;
        this.employees = employees;
        this.settings = settings;
        this.config = config;
        this.json = json;
    }

    /** Whether a crop may become a template, with the prototype's reason. */
    public record Eligibility(boolean ok, String why) {}

    /** A crossing's face crop as a template candidate. */
    public record Crop(@Nullable String faceCrop, @Nullable String quality, boolean backView, boolean purged) {

        boolean stored() {
            return faceCrop != null && !purged;
        }
    }

    /**
     * The prototype's {@code templateEligibility}.
     *
     * @param employee the person the template would belong to; null skips the consent check (no one chosen yet)
     */
    public Eligibility eligibility(@Nullable UUID employee, @Nullable String employeeName, Crop crop) {
        if (employee != null && !"GIVEN".equals(employees.consentStatus(employee))) {
            return new Eligibility(
                    false, firstName(employeeName) + " has no consent on file — templates can’t be added");
        }
        JsonNode q = crop.quality() == null ? null : json.readTree(crop.quality());
        if (q == null) {
            return new Eligibility(false, "No quality data for this crop");
        }
        if (crop.backView()) {
            return new Eligibility(false, "Back-view crops can’t become face templates");
        }
        int minEyePx = settings.enrolment().minEyePx();
        int eyePx = q.path("eye_px").asInt(0);
        if (eyePx < minEyePx) {
            return new Eligibility(
                    false,
                    "Face too small for a template (" + eyePx + " px between the eyes; enrolment needs " + minEyePx
                            + " px)");
        }
        int sharp = q.path("sharp").asInt(0);
        if (sharp < MIN_SHARPNESS) {
            return new Eligibility(false, "Not sharp enough for a template (sharpness " + sharp + ")");
        }
        long pose = Math.round(Math.max(
                Math.abs(q.path("yaw").asDouble(0)), Math.abs(q.path("pitch").asDouble(0))));
        if (pose > MAX_POSE_DEGREES) {
            return new Eligibility(false, "Head turned too far for a template (" + pose + "°)");
        }
        if (!crop.stored()) {
            return new Eligibility(false, "The crop is no longer stored");
        }
        return new Eligibility(true, "Passes the enrolment quality threshold");
    }

    /**
     * Whether an identified crossing's face crop can become the employee's template now (D-118): eligible as a
     * review crop, not already a template, and never for the operator's own face profile.
     *
     * @param operator the user asking (null when unknown)
     */
    public Eligibility offer(
            UUID employeeId, String employeeName, long crossingId, Crop crop, @Nullable UUID operator) {
        if (employeeId.equals(operator)) {
            return new Eligibility(false, "You can’t add a template to your own face profile — ask another operator");
        }
        if (store.activeForCrossing(crossingId)) {
            return new Eligibility(false, "This face crop is already a template");
        }
        return eligibility(employeeId, employeeName, crop);
    }

    /**
     * @param faceCrop the encrypted face crop under {@code crops/}
     * @return the new template's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addFromReview(
            UUID employeeId, String faceCrop, long reviewItemId, String reviewCode, AuditActor actor) {
        long id = addFromCrop(employeeId, faceCrop, "REVIEW", reviewItemId, null, actor);
        faces.templateAddedFromReview(employeeId, reviewCode, actor);
        return id;
    }

    /**
     * A template from the face crop of an identified crossing (D-118): as {@link #offer} allows, and not looking like
     * another enrolled employee.
     *
     * @return the new template's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addFromCrossing(
            UUID employeeId, String employeeName, long crossingId, String crossingCode, Crop crop, AuditActor actor) {
        Eligibility offer = offer(employeeId, employeeName, crossingId, crop, actor.employeeId());
        if (!offer.ok()) {
            throw new Errors.BusinessRule("TEMPLATE_NOT_ELIGIBLE", offer.why());
        }
        long id = addFromCrop(employeeId, crop.faceCrop(), "CROSSING", null, crossingId, actor);
        faces.templateAddedFromCrossing(employeeId, crossingCode, actor);
        return id;
    }

    private long addFromCrop(
            UUID employeeId,
            @Nullable String faceCrop,
            String source,
            @Nullable Long reviewItemId,
            @Nullable Long crossingId,
            AuditActor actor) {
        byte[] stored = faceCrop == null
                ? null
                : files.read(FileStore.Folder.CROPS, faceCrop).orElse(null);
        if (stored == null) {
            throw new Errors.BusinessRule("TEMPLATE_NOT_ELIGIBLE", "The crop is no longer stored.");
        }
        WorkerClient.FaceEmbedding face =
                worker.embeddings(List.of(crypto.decrypt(stored)), "upload").getFirst();
        if (face.faces() != 1 || face.embedding() == null || face.dim() == null) {
            throw new Errors.BusinessRule(
                    "TEMPLATE_NOT_ELIGIBLE", "The crop doesn’t show one clear face, so no template was added.");
        }
        if ("CROSSING".equals(source)) {
            lookalikeCheck(employeeId, face.embedding());
        }
        String thumbnail = THUMBNAILS + employeeId + "/" + UUID.randomUUID() + ".jpg";
        files.write(FileStore.Folder.UPLOADS, thumbnail, stored);
        AfterCommit.onRollback(() -> files.delete(FileStore.Folder.UPLOADS, thumbnail));
        String[] model = modelParts(face.model());
        long id = store.insert(new TemplateStore.NewTemplate(
                employeeId,
                crypto.encrypt(face.embedding()),
                crypto.activeKeyId(),
                model[0],
                model[1],
                face.dim(),
                face.quality() == null ? null : face.quality().score(),
                face.quality() == null ? null : face.quality().eyePx(),
                "FRONT",
                source,
                thumbnail,
                reviewItemId,
                crossingId,
                null,
                actor.employeeId()));
        config.publishGalleryAfterCommit(employeeId);
        return id;
    }

    /** A crop that looks like another enrolled employee never becomes this employee's template. */
    private void lookalikeCheck(UUID employeeId, byte[] embedding) {
        var similar = worker.duplicates(
                List.of(embedding), employeeId.toString(), settings.enrolment().dupThreshold());
        if (similar.isEmpty()) {
            return;
        }
        UUID other = UUID.fromString(similar.getFirst().employeeId());
        var person = employees.summaries(List.of(other)).get(other);
        throw new Errors.BusinessRule(
                "LOOKS_LIKE_SOMEONE_ELSE",
                "This face looks like " + (person == null ? "another employee" : person.displayName())
                        + ", so no template was added.");
    }

    /**
     * A template from an accepted enrolment capture (R15 save).
     *
     * @param embeddingEnc the capture's encrypted embedding
     * @param image the capture's encrypted image (it becomes the thumbnail)
     * @param source {@code LIVE}, {@code UPLOAD} or {@code CAMERA}
     * @param light DAY, EVENING or NIGHT_IR for a capture at an entry point, else null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addFromEnrolment(
            UUID employeeId,
            byte[] embeddingEnc,
            String model,
            int dim,
            Integer quality,
            Integer eyePx,
            String pose,
            String source,
            @Nullable String light,
            byte[] image,
            UUID createdBy) {
        String thumbnail = THUMBNAILS + employeeId + "/" + UUID.randomUUID() + ".jpg";
        files.write(FileStore.Folder.UPLOADS, thumbnail, image);
        AfterCommit.onRollback(() -> files.delete(FileStore.Folder.UPLOADS, thumbnail));
        String[] modelParts = modelParts(model);
        return store.insert(new TemplateStore.NewTemplate(
                employeeId,
                embeddingEnc,
                crypto.keyIdOf(embeddingEnc),
                modelParts[0],
                modelParts[1],
                dim,
                quality,
                eyePx,
                pose,
                source,
                thumbnail,
                null,
                null,
                light,
                createdBy));
    }

    /** "sface-2021dec" → name "sface", version "2021dec" (the whole text twice when it has no dash). */
    private static String[] modelParts(String model) {
        String text = model == null ? "unknown" : model;
        int dash = text.lastIndexOf('-');
        return dash > 0 ? new String[] {text.substring(0, dash), text.substring(dash + 1)} : new String[] {text, text};
    }

    private static String firstName(@Nullable String name) {
        if (name == null) {
            return "This employee";
        }
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    /** The undo of a review decision: the template it added goes again, its thumbnail after the commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeFromReview(long templateId) {
        store.owned(templateId).ifPresent(owned -> {
            store.delete(templateId);
            if (owned.thumbnailPath() != null) {
                AfterCommit.run(() -> files.delete(FileStore.Folder.UPLOADS, owned.thumbnailPath()));
            }
            config.publishGalleryAfterCommit(owned.employeeId());
        });
    }
}
