package com.krztechnologies.attend.privacy.service;

import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.privacy.repository.RetentionQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases one employee's biometric data (R17): face templates with their thumbnails, enrolment captures with their
 * images, and the crops of their crossings. Crossings and attendance stay (without images); the consent record and
 * any withdrawal stay as proof. The face status becomes ERASED, tracking Not tracked (No face), and the profile history
 * gets an "ERASED" entry. The worker hears it on {@code krz:config} (gallery) after the commit, and its version poll
 * sees the gallery-version triggers if that message is missed. If a file cannot be
 * deleted, nothing is marked erased (the transaction rolls back) so the erasure is tried again. The caller audits.
 */
@Service
public class BiometricErasureService {

    private final RetentionQueries queries;
    private final MediaPurge media;
    private final TimeService time;
    private final ConfigPublisher config;
    private final ApplicationEventPublisher events;

    public BiometricErasureService(
            RetentionQueries queries,
            MediaPurge media,
            TimeService time,
            ConfigPublisher config,
            ApplicationEventPublisher events) {
        this.queries = queries;
        this.media = media;
        this.time = time;
        this.config = config;
        this.events = events;
    }

    /**
     * What was erased.
     *
     * @param files template thumbnails, enrolment images and crops deleted from disk
     */
    public record ErasureResult(int templates, int captures, int crossings, int files) {}

    @Transactional
    public ErasureResult erase(UUID employeeId, String reason, AuditActor actor) {
        var templates = media.deleteUploads(queries.templatesOfEmployee(employeeId));
        var captures = media.deleteUploads(queries.captureImagesOfEmployee(employeeId));
        var crops = media.deleteCrops(queries.cropsOfEmployee(employeeId));
        if (!templates.failedRows().isEmpty() || !captures.failedRows().isEmpty() || crops.failures() > 0) {
            throw new IllegalStateException("Some biometric files could not be deleted; the erasure is retried");
        }
        int templateRows = queries.deleteTemplates(templates.removedRows());
        int captureRows = queries.deleteCaptures(captures.removedRows());
        int crossings = queries.markCropsPurged(crops.purgedCrossings(), time.now());
        queries.markErased(employeeId, reason, actor.employeeId(), actor.label(), time.now());
        config.publishGalleryAfterCommit(employeeId);
        events.publishEvent(AttendanceAffected.employees(List.of(employeeId), time.today(), time.today()));
        return new ErasureResult(
                templateRows,
                captureRows,
                crossings,
                templates.filesDeleted() + captures.filesDeleted() + crops.filesDeleted());
    }
}
