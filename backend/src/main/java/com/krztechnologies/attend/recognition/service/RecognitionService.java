package com.krztechnologies.attend.recognition.service;

import com.krztechnologies.attend.camera.service.CameraService;
import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.redis.ConfigSignal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.settings.EnrolmentSettings;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.recognition.domain.ThresholdProfile;
import com.krztechnologies.attend.recognition.domain.ThresholdValues;
import com.krztechnologies.attend.recognition.repository.ImpactQueries;
import com.krztechnologies.attend.recognition.repository.ThresholdProfileRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings → Recognition and Enrolment &amp; quality. Threshold profiles are immutable: saving creates TP-n and makes
 * it the only active one; switching back activates an older one. Every change is audited (category Recognition) and
 * publishes {@code krz:config recognition} after the commit. The impact preview re-scores the last 14 days.
 */
@Service
public class RecognitionService {

    static final String DEFAULT_NOTE = "Edited in Settings";
    private static final int IMPACT_DAYS = 14;

    private final ThresholdProfileRepository profiles;
    private final ImpactQueries impact;
    private final CameraService cameras;
    private final ModelInfoService models;
    private final SettingsService settings;
    private final IdGenerator ids;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final ConfigPublisher config;
    private final TimeService time;
    private final JsonMapper json;

    public RecognitionService(
            ThresholdProfileRepository profiles,
            ImpactQueries impact,
            CameraService cameras,
            ModelInfoService models,
            SettingsService settings,
            IdGenerator ids,
            AuditService audit,
            CurrentUser currentUser,
            ConfigPublisher config,
            TimeService time,
            JsonMapper json) {
        this.profiles = profiles;
        this.impact = impact;
        this.cameras = cameras;
        this.models = models;
        this.settings = settings;
        this.ids = ids;
        this.audit = audit;
        this.currentUser = currentUser;
        this.config = config;
        this.time = time;
        this.json = json;
    }

    /** Active values, every profile, the liveness flags and the model info (no transaction held for the worker). */
    public RecognitionViews.RecognitionView view() {
        List<RecognitionViews.ProfileView> all =
                profiles.findAllByOrderByIdDesc().stream().map(this::view).toList();
        return new RecognitionViews.RecognitionView(
                all.stream()
                        .filter(RecognitionViews.ProfileView::active)
                        .findFirst()
                        .orElse(null),
                all,
                cameras.livenessFlags(),
                models.current());
    }

    /**
     * Saves new values as a new profile and activates it. {@code basedOn} is the profile the edit started from; when
     * another profile became active meanwhile the save is refused (409 STALE_VERSION). Unchanged values create
     * nothing.
     */
    @Transactional
    public void save(ThresholdValues values, String note, String basedOn) {
        if (values.agreeK() > values.agreeN()) {
            throw new Errors.BusinessRule(
                    "VALIDATION",
                    "Some fields are not valid.",
                    List.of(new ErrorResponse.FieldError("values.agreeK", "Must not be more than agreeN.")));
        }
        Optional<ThresholdProfile> active = profiles.findForUpdateByActiveTrue();
        if (active.isPresent() && !active.get().getCode().equals(basedOn)) {
            throw new Errors.Conflict(
                    "STALE_VERSION", "Someone else changed the thresholds. Reload to see the latest.");
        }
        ThresholdValues before = active.map(this::values).orElse(null);
        if (values.equals(before)) {
            return;
        }
        var me = currentUser.require();
        ThresholdProfile created = new ThresholdProfile(
                ids.next(CodeType.THRESHOLD_PROFILE),
                json.writeValueAsString(values),
                note == null || note.isBlank() ? DEFAULT_NOTE : note.trim(),
                me.employeeId(),
                me.displayName(),
                time.now());
        switchTo(active, created);
        List<String> changed = changedKeys(before, values);
        audit.record(
                AuditCategory.RECOGNITION,
                "Updated Recognition",
                "Recognition",
                describe(before, changed),
                describe(values, changed),
                Map.of("profile", created.getCode()));
    }

    /** Switches back to an earlier profile; applies to new crossings only. */
    @Transactional
    public void activate(String code) {
        ThresholdProfile target =
                profiles.findByCode(code).orElseThrow(() -> new Errors.NotFound("Threshold profile not found."));
        Optional<ThresholdProfile> active = profiles.findForUpdateByActiveTrue();
        if (target.isActive()) {
            return;
        }
        String before = active.map(ThresholdProfile::getCode).orElse(null);
        switchTo(active, target);
        audit.record(
                AuditCategory.RECOGNITION,
                "Switched threshold profile",
                target.getCode(),
                before,
                target.getCode(),
                null);
    }

    /** The per-camera liveness check of the Liveness section. */
    @Transactional
    public CameraViews.CameraLiveness setLiveness(String cameraCode, boolean enabled) {
        return cameras.setLiveness(cameraCode, enabled);
    }

    /**
     * The impact preview: the last 14 days of face scores split by the given values (missing ones are taken from the
     * active profile) and by the active profile.
     */
    @Transactional(readOnly = true)
    public RecognitionViews.ImpactView impact(Double accept, Double reviewBand, Double margin) {
        ThresholdProfile active =
                profiles.findByActiveTrue().orElseThrow(() -> new IllegalStateException("No active threshold profile"));
        ThresholdValues current = values(active);
        double a = accept == null ? current.accept() : accept;
        double band = reviewBand == null ? current.reviewBand() : reviewBand;
        double m = margin == null ? current.margin() : margin;
        Instant to = time.now();
        Instant from = to.minus(Duration.ofDays(IMPACT_DAYS));
        long[] bins = impact.histogram(from, to);
        RecognitionViews.Split proposed = split(impact.split(from, to, a, band, m));
        return new RecognitionViews.ImpactView(
                from,
                to,
                IMPACT_DAYS,
                Arrays.stream(bins).sum(),
                ImpactQueries.LOW,
                ImpactQueries.HIGH,
                Arrays.stream(bins).boxed().toList(),
                a,
                band,
                m,
                proposed,
                active.getCode(),
                split(impact.split(from, to, current.accept(), current.reviewBand(), current.margin())));
    }

    @Transactional(readOnly = true)
    public RecognitionViews.EnrolmentView enrolment() {
        SettingsService.Versioned<EnrolmentSettings> current = settings.get(SettingsArea.ENROLMENT);
        return new RecognitionViews.EnrolmentView(current.value(), current.version());
    }

    /** Saves Enrolment &amp; quality (audited by the settings service); 409 STALE_VERSION on a concurrent save. */
    @Transactional
    public RecognitionViews.EnrolmentView updateEnrolment(EnrolmentSettings values, int version) {
        SettingsService.Versioned<EnrolmentSettings> saved = settings.update(SettingsArea.ENROLMENT, values, version);
        return new RecognitionViews.EnrolmentView(saved.value(), saved.version());
    }

    /** Deactivates the old profile first (one active profile at a time, enforced by a unique index). */
    private void switchTo(Optional<ThresholdProfile> active, ThresholdProfile next) {
        active.ifPresent(old -> {
            old.deactivate();
            profiles.saveAndFlush(old);
        });
        next.activate();
        ThresholdProfile saved = profiles.saveAndFlush(next);
        config.publishAfterCommit(ConfigSignal.recognition(saved.getId()));
    }

    /** The bands of the active threshold profile (Events, Review, Today). */
    @Transactional(readOnly = true)
    public ConfidenceBands confidenceBands() {
        return new ConfidenceBands(activeValues().accept());
    }

    /** The values of the active threshold profile (the quality chips and the review reasons). */
    @Transactional(readOnly = true)
    public ActiveThresholds activeValues() {
        ThresholdValues v = values(profiles.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active threshold profile")));
        return new ActiveThresholds(
                v.accept(), v.reviewBand(), v.margin(), v.minEyePx(), v.maxYaw(), v.sharpMin(), v.reidThreshold());
    }

    private ThresholdValues values(ThresholdProfile profile) {
        return json.readValue(profile.getProfileValues(), ThresholdValues.class);
    }

    private RecognitionViews.ProfileView view(ThresholdProfile profile) {
        return new RecognitionViews.ProfileView(
                profile.getCode(),
                profile.isActive(),
                values(profile),
                profile.getNote(),
                profile.getCreatedByLabel(),
                profile.getCreatedAt());
    }

    private List<String> changedKeys(ThresholdValues before, ThresholdValues after) {
        JsonNode old = before == null ? json.createObjectNode() : json.valueToTree(before);
        JsonNode now = json.valueToTree(after);
        List<String> changed = new ArrayList<>();
        for (String key : now.propertyNames()) {
            if (!now.get(key).equals(old.get(key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    /** The prototype's audit summary: "accept: 0.42; margin: 0.06" for the changed keys. */
    private String describe(ThresholdValues values, List<String> keys) {
        if (values == null) {
            return null;
        }
        JsonNode tree = json.valueToTree(values);
        return String.join("; ", keys.stream().map(k -> k + ": " + tree.get(k)).toList());
    }

    private static RecognitionViews.Split split(ImpactQueries.Split split) {
        return new RecognitionViews.Split(split.accepted(), split.review(), split.unknown());
    }
}
