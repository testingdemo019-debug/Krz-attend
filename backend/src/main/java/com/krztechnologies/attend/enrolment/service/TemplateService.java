package com.krztechnologies.attend.enrolment.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.employee.service.FaceProfileService;
import com.krztechnologies.attend.enrolment.repository.TemplateStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Face templates: from accepted enrolment captures (R15), and from a crop of the Review Queue (R12, source REVIEW)
 * whose undo removes it again. Embeddings are stored encrypted and never leave the backend; the capture's or crop's
 * encrypted image becomes the template's thumbnail.
 */
@Service
public class TemplateService {

    private static final String THUMBNAILS = "templates/";

    private final TemplateStore store;
    private final FileStore files;
    private final CryptoService crypto;
    private final WorkerClient worker;
    private final FaceProfileService faces;
    private final ConfigPublisher config;

    public TemplateService(
            TemplateStore store,
            FileStore files,
            CryptoService crypto,
            WorkerClient worker,
            FaceProfileService faces,
            ConfigPublisher config) {
        this.store = store;
        this.files = files;
        this.crypto = crypto;
        this.worker = worker;
        this.faces = faces;
        this.config = config;
    }

    /**
     * @param faceCrop the encrypted face crop under {@code crops/}
     * @return the new template's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addFromReview(
            UUID employeeId, String faceCrop, long reviewItemId, String reviewCode, AuditActor actor) {
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
                "REVIEW",
                thumbnail,
                reviewItemId,
                actor.employeeId()));
        faces.templateAddedFromReview(employeeId, reviewCode, actor);
        config.publishGalleryAfterCommit(employeeId);
        return id;
    }

    /**
     * A template from an accepted enrolment capture (R15 save).
     *
     * @param embeddingEnc the capture's encrypted embedding
     * @param image the capture's encrypted image (it becomes the thumbnail)
     * @param source {@code LIVE} or {@code UPLOAD}
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
                createdBy));
    }

    /** "sface-2021dec" → name "sface", version "2021dec" (the whole text twice when it has no dash). */
    private static String[] modelParts(String model) {
        String text = model == null ? "unknown" : model;
        int dash = text.lastIndexOf('-');
        return dash > 0 ? new String[] {text.substring(0, dash), text.substring(dash + 1)} : new String[] {text, text};
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
