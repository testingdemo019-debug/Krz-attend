package com.krztechnologies.attend.recognition.service;

import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.common.settings.EnrolmentSettings;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.recognition.domain.ThresholdValues;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models returned by the recognition services (records; mapped to the API as they are). */
public final class RecognitionViews {

    private RecognitionViews() {}

    /**
     * A threshold profile.
     *
     * @param createdBy who saved it ("System" for the seeded TP-1)
     */
    public record ProfileView(
            String code,
            boolean active,
            ThresholdValues values,
            @Nullable String note,
            String createdBy,
            Instant createdAt) {}

    /**
     * The models the worker runs, from its {@code /health} (cached for 60 s).
     *
     * @param available false when the worker could not be asked; {@code errorCode} says why
     * @param status ok, degraded or starting
     */
    public record ModelInfo(
            boolean available,
            @Nullable String status,
            @Nullable String version,
            @Nullable String provider,
            List<WorkerClient.Model> models,
            @Nullable String errorCode) {}

    /**
     * Settings → Recognition.
     *
     * @param active the active profile (its values are what the worker uses)
     * @param profiles every profile, newest first
     * @param liveness the per-camera liveness flags (not part of a profile)
     */
    public record RecognitionView(
            ProfileView active,
            List<ProfileView> profiles,
            List<CameraViews.CameraLiveness> liveness,
            ModelInfo model) {}

    /** How many crossings would be matched, sent to review or left unknown. */
    public record Split(long accepted, long review, long unknown) {}

    /**
     * The impact preview: the face scores of the last 14 days scored again.
     *
     * @param bins crossing counts per score bin, {@code binCount} bins from {@code low} to {@code high}
     * @param proposed the split with the values asked for
     * @param current the split with the active profile
     */
    public record ImpactView(
            Instant from,
            Instant to,
            int days,
            long total,
            double low,
            double high,
            List<Long> bins,
            double accept,
            double reviewBand,
            double margin,
            Split proposed,
            String currentProfile,
            Split current) {}

    /** Settings → Enrolment &amp; quality with the version to send back when saving. */
    public record EnrolmentView(EnrolmentSettings settings, int version) {}
}
