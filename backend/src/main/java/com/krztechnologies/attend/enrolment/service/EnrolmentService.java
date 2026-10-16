package com.krztechnologies.attend.enrolment.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.common.media.Images;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.settings.EnrolmentSettings;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.employee.service.ConsentService;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.FaceProfileService;
import com.krztechnologies.attend.enrolment.repository.EnrolmentStore;
import com.krztechnologies.attend.enrolment.repository.TemplateStore;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import com.krztechnologies.attend.privacy.service.ConsentNoticeService;
import com.krztechnologies.attend.recognition.service.ActiveThresholds;
import com.krztechnologies.attend.recognition.service.RecognitionService;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Face enrolment (R15): a session per employee (one open at a time, 60 minutes), consent against the active notice
 * (given with the signed form, or declined), captures checked by the worker (one face, quality, eye distance, and
 * liveness in live mode when required; uploads also for exposure and pose), liveness lockout with NR-02, the duplicate
 * check against other enrolled employees, and the save: templates (encrypted), face profile, tracking, history, audit,
 * gallery signal. Unsaved captures are discarded when the session ends.
 */
@Service
public class EnrolmentService {

    private static final Duration SESSION_LENGTH = Duration.ofMinutes(60);
    private static final int MAX_CAPTURES = 20;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
    private static final int MIN_SIDE = 64;
    private static final int MAX_SIDE = 4096;
    private static final float JPEG_QUALITY = 0.92f;
    private static final int TOO_DARK = 65;
    private static final int TOO_BRIGHT = 215;
    private static final double MAX_UPLOAD_YAW = 22;
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final Set<String> POSES = Set.of("FRONT", "TURN_LEFT", "TURN_RIGHT", "CHIN_DOWN");
    private static final int SEARCH_LIMIT = 12;

    private final EnrolmentStore store;
    private final TemplateStore templateStore;
    private final TemplateService templates;
    private final FaceProfileService faces;
    private final ConsentService consents;
    private final ConsentNoticeService notices;
    private final EmployeeDirectory employees;
    private final RecognitionService recognition;
    private final WorkerClient worker;
    private final CryptoService crypto;
    private final FileStore files;
    private final NotificationService notifications;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final SettingsService settings;
    private final TimeService time;
    private final JsonMapper json;

    public EnrolmentService(
            EnrolmentStore store,
            TemplateStore templateStore,
            TemplateService templates,
            FaceProfileService faces,
            ConsentService consents,
            ConsentNoticeService notices,
            EmployeeDirectory employees,
            RecognitionService recognition,
            WorkerClient worker,
            CryptoService crypto,
            FileStore files,
            NotificationService notifications,
            AuditService audit,
            CurrentUser currentUser,
            SettingsService settings,
            TimeService time,
            JsonMapper json) {
        this.store = store;
        this.templateStore = templateStore;
        this.templates = templates;
        this.faces = faces;
        this.consents = consents;
        this.notices = notices;
        this.employees = employees;
        this.recognition = recognition;
        this.worker = worker;
        this.crypto = crypto;
        this.files = files;
        this.notifications = notifications;
        this.audit = audit;
        this.currentUser = currentUser;
        this.settings = settings;
        this.time = time;
        this.json = json;
    }

    /** {@code LIVE} (guided camera with liveness) or {@code UPLOAD} (photos). */
    public enum Method {
        LIVE,
        UPLOAD
    }

    /** Replace all active templates (default) or add to them. */
    public enum Mode {
        REPLACE,
        ADD
    }

    /** The signed consent form as uploaded. */
    public record UploadedDocument(byte[] content) {}

    /** Enabled employees at a location with a camera who are not enrolled, need re-enrolment or wait for consent. */
    @Transactional(readOnly = true)
    public EnrolmentViews.Queue queue() {
        Instant now = time.now();
        var cards = employees.cards(store.queue());
        return new EnrolmentViews.Queue(
                cards.values().stream()
                        .sorted(Comparator.comparing((EmployeeDirectory.Card c) -> !c.tracked())
                                .thenComparing(EmployeeDirectory.Card::displayName, String.CASE_INSENSITIVE_ORDER))
                        .map(c -> row(c, now))
                        .toList(),
                store.cameraLocations());
    }

    /** Anyone by name or Employee ID, at most 12 (the prototype's search over every employee). */
    @Transactional(readOnly = true)
    public List<EnrolmentViews.QueueRow> search(String q) {
        Instant now = time.now();
        List<UUID> ids = employees.search(q, SEARCH_LIMIT);
        var cards = employees.cards(ids);
        return ids.stream()
                .map(cards::get)
                .filter(Objects::nonNull)
                .map(c -> row(c, now))
                .toList();
    }

    /** What the wizard shows about the person chosen (the prototype's "Selected" panel). */
    @Transactional(readOnly = true)
    public EnrolmentViews.Candidate candidate(UUID employeeId) {
        var card = employees.cards(List.of(employeeId)).get(employeeId);
        if (card == null) {
            throw new Errors.NotFound("Employee not found.");
        }
        Instant now = time.now();
        boolean sensitive = currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE);
        var active = templateStore.active(employeeId).stream()
                .map(t -> new EnrolmentViews.TemplateRef(
                        t.id(),
                        t.pose(),
                        t.quality(),
                        sensitive && t.hasThumbnail() ? FaceProfileService.thumbnailUrl(employeeId, t.id()) : null))
                .toList();
        return new EnrolmentViews.Candidate(
                person(card),
                card.accountEnabled(),
                card.faceStatus(),
                consent(employeeId),
                card.trackingLabel(),
                active,
                store.lockedUntil(employeeId, now).orElse(null),
                failures(employeeId, now));
    }

    /** Settings → Enrolment & quality, as the wizard explains and checks them. */
    @Transactional(readOnly = true)
    public EnrolmentSettings rules() {
        return settings.enrolment();
    }

    /**
     * Test recognition after saving: whom the gallery finds in one camera frame. Nothing is stored; the run is
     * audited, as the prototype's was.
     */
    @Transactional
    public EnrolmentViews.TestResult test(UUID employeeId, byte[] upload) {
        var card = employees.cards(List.of(employeeId)).get(employeeId);
        if (card == null) {
            throw new Errors.NotFound("Employee not found.");
        }
        WorkerClient.FaceEmbedding face =
                worker.embeddings(List.of(reEncode(upload)), "live").getFirst();
        EnrolmentViews.TestResult result = new EnrolmentViews.TestResult(face.faces(), false, null, null);
        if (face.faces() == 1 && face.embedding() != null) {
            ActiveThresholds t = recognition.activeValues();
            var best =
                    worker
                            .duplicates(List.of(face.embedding()), null, Math.max(0, t.accept() - t.reviewBand()))
                            .stream()
                            .findFirst();
            if (best.isPresent()) {
                UUID id = parseId(best.get().employeeId());
                var found = id == null ? null : employees.cards(List.of(id)).get(id);
                BigDecimal score = BigDecimal.valueOf(best.get().score());
                result = new EnrolmentViews.TestResult(
                        1,
                        employeeId.equals(id) && best.get().score() >= t.accept(),
                        found == null ? null : found.displayName(),
                        recognition.confidenceBands().band(score));
            }
        }
        audit.record(
                AuditCategory.ENROLMENT,
                "Ran test recognition",
                card.displayName(),
                null,
                result.matched() ? "Matched · " + bandLabel(result.confidence()) : "Not matched",
                null);
        return result;
    }

    @Transactional
    public EnrolmentViews.Session start(UUID employeeId, Method method) {
        var card = employees.cards(List.of(employeeId)).get(employeeId);
        if (card == null || !card.accountEnabled()) {
            throw invalid("employeeId", "Choose an enabled employee.");
        }
        Instant now = time.now();
        lockedCheck(employeeId, now);
        if (method == Method.UPLOAD && !settings.enrolment().allowUpload()) {
            throw new Errors.BusinessRule(
                    "UPLOAD_DISABLED", "Photo upload is turned off in Settings → Enrolment & quality.");
        }
        store.openOf(employeeId).ifPresent(previous -> end(previous, "CANCELLED"));
        AttendPrincipal me = currentUser.require();
        var session = store.start(employeeId, method.name(), me.employeeId(), now, now.plus(SESSION_LENGTH));
        return view(session);
    }

    /**
     * Records the consent decision for the active notice.
     *
     * @param method required for GIVEN
     * @param document the signed form (PDF, JPEG or PNG, at most 5 MB), or null
     */
    @Transactional
    public EnrolmentViews.Session consent(
            String code, boolean given, ConsentService.Method method, int noticeVersion, UploadedDocument document) {
        var session = open(code);
        int active = notices.active().version();
        if (noticeVersion != active) {
            throw new Errors.Conflict(
                    "NOTICE_CHANGED", "The notice changed. Read version " + active + " with the employee.");
        }
        AuditActor actor = actor();
        if (!given) {
            String before = consents.current(session.employeeId())
                    .map(c -> label(c.status()))
                    .orElse("None");
            faces.declined(session.employeeId(), active, before, actor);
            end(session, "DECLINED");
            return view(store.lock(code).orElseThrow());
        }
        if (method == null) {
            throw invalid("method", "Choose how consent was obtained.");
        }
        ConsentService.Document stored = document == null ? null : storeDocument(session.employeeId(), document);
        long consentId = consents.give(session.employeeId(), method, active, stored, actor);
        store.consentGiven(session.id(), consentId);
        return view(store.lock(code).orElseThrow());
    }

    /**
     * One capture or uploaded photo, checked by the worker.
     *
     * @param pose FRONT, TURN_LEFT, TURN_RIGHT or CHIN_DOWN (live); null for uploads
     */
    @Transactional
    public EnrolmentViews.CaptureResult capture(String code, byte[] upload, String pose) {
        var session = open(code);
        Instant now = time.now();
        lockedCheck(session.employeeId(), now);
        consentCheck(session.employeeId());
        if (pose != null && !POSES.contains(pose)) {
            throw invalid("pose", "Use FRONT, TURN_LEFT, TURN_RIGHT or CHIN_DOWN.");
        }
        if (store.captures(session.id()).size() >= MAX_CAPTURES) {
            throw new Errors.BusinessRule(
                    "TOO_MANY_CAPTURES", "This session has " + MAX_CAPTURES + " captures. Remove some first.");
        }
        byte[] jpeg = reEncode(upload);
        boolean live = Method.LIVE.name().equals(session.method());
        WorkerClient.FaceEmbedding face =
                worker.embeddings(List.of(jpeg), live ? "live" : "upload").getFirst();
        EnrolmentSettings rules = settings.enrolment();
        List<String> reasons = reasons(face, live, rules);
        boolean livenessFailed = live && rules.livenessRequired() && !Boolean.TRUE.equals(face.livenessPassed());
        if (livenessFailed && face.faces() == 1) {
            reasons.add("Liveness check failed — follow the prompt slowly");
        }
        boolean accepted = reasons.isEmpty() && face.embedding() != null && face.dim() != null;
        if (reasons.isEmpty() && !accepted) {
            reasons.add("No face found — use a clear, front-facing photo");
        }
        String path = "enrolment/" + session.code() + "/" + UUID.randomUUID() + ".jpg";
        files.write(FileStore.Folder.UPLOADS, path, crypto.encrypt(jpeg));
        AfterCommit.onRollback(() -> files.delete(FileStore.Folder.UPLOADS, path));
        long id = store.addCapture(new EnrolmentStore.NewCapture(
                session.id(),
                pose,
                path,
                accepted ? crypto.encrypt(face.embedding()) : null,
                qualityJson(face),
                face.livenessPassed(),
                face.livenessScore() == null ? null : BigDecimal.valueOf(face.livenessScore()),
                accepted,
                accepted ? null : truncate(String.join("; ", reasons)),
                now));
        Integer attempts = null;
        Instant lockedUntil = null;
        if (livenessFailed && face.faces() == 1) {
            attempts = livenessFailure(session, rules, now);
            lockedUntil = store.lockedUntil(session.employeeId(), now).orElse(null);
        }
        List<EnrolmentStore.Capture> all = store.captures(session.id());
        var saved = all.stream().filter(c -> c.id() == id).findFirst().orElseThrow();
        int good = (int) all.stream().filter(EnrolmentStore.Capture::accepted).count();
        return new EnrolmentViews.CaptureResult(capture(saved), good, attempts, lockedUntil);
    }

    @Transactional
    public EnrolmentViews.Session removeCapture(String code, long captureId) {
        var session = open(code);
        var capture = store.captures(session.id()).stream()
                .filter(c -> c.id() == captureId)
                .findFirst()
                .orElseThrow(() -> new Errors.NotFound("Capture not found."));
        store.deleteCapture(capture.id());
        deleteAfterCommit(capture.imagePath());
        return view(session);
    }

    /** Other enrolled employees whose faces resemble the accepted captures (at least the duplicate threshold). */
    @Transactional(readOnly = true)
    public EnrolmentViews.DuplicateCheck duplicateCheck(String code) {
        var session = store.find(code).orElseThrow(() -> new Errors.NotFound("Enrolment session not found."));
        var accepted = store.captures(session.id()).stream()
                .filter(EnrolmentStore.Capture::accepted)
                .toList();
        if (accepted.isEmpty()) {
            throw new Errors.BusinessRule("NO_CAPTURES", "Add an accepted capture first.");
        }
        return new EnrolmentViews.DuplicateCheck(
                settings.enrolment().dupThreshold(), lookalikes(session.employeeId(), accepted));
    }

    /**
     * Saves the enrolment from the chosen accepted captures.
     *
     * @param lookalikeConfirmed the operator confirmed the lookalikes are different people
     */
    @Transactional
    public EnrolmentViews.Completed complete(
            String code, List<Long> captureIds, boolean lookalikeConfirmed, Mode mode) {
        var session = open(code);
        consentCheck(session.employeeId());
        EnrolmentSettings rules = settings.enrolment();
        Map<Long, EnrolmentStore.Capture> byId = new LinkedHashMap<>();
        store.captures(session.id()).forEach(c -> byId.put(c.id(), c));
        List<EnrolmentStore.Capture> chosen = new ArrayList<>();
        for (Long id : captureIds) {
            EnrolmentStore.Capture c = byId.get(id);
            if (c == null || !c.accepted()) {
                throw invalid("captureIds", "Capture " + id + " is not an accepted capture of this session.");
            }
            chosen.add(c);
        }
        if (chosen.size() < rules.minGood()) {
            throw new Errors.BusinessRule(
                    "NOT_ENOUGH_CAPTURES",
                    "At least " + rules.minGood() + " good images are needed (you have " + chosen.size() + ").");
        }
        AuditActor actor = actor();
        var card = employees.cards(List.of(session.employeeId())).get(session.employeeId());
        List<EnrolmentViews.Lookalike> lookalikes = lookalikes(session.employeeId(), chosen);
        if (!lookalikes.isEmpty()) {
            if (!lookalikeConfirmed) {
                var first = lookalikes.getFirst();
                throw new Errors.BusinessRule(
                        "LOOKALIKE_CONFIRMATION",
                        "Looks similar to " + first.name() + " ("
                                + (first.employeeIdNumber() == null ? "no Employee ID" : first.employeeIdNumber())
                                + ") — confirm these are different people before saving.");
            }
            store.lookalikeConfirmed(session.id());
            audit.record(
                    actor,
                    AuditCategory.ENROLMENT,
                    "Confirmed different people despite a similar face",
                    card.displayName(),
                    null,
                    "Looks like "
                            + lookalikes.stream()
                                    .map(EnrolmentViews.Lookalike::name)
                                    .collect(Collectors.joining(", ")),
                    null);
        }
        Instant now = time.now();
        int before = templateStore.activeCount(session.employeeId());
        if (mode != Mode.ADD) {
            templateStore.deactivateActive(session.employeeId(), now);
        }
        boolean live = Method.LIVE.name().equals(session.method());
        for (EnrolmentStore.Capture c : chosen) {
            JsonNode q = json.readTree(c.quality());
            byte[] image = files.read(FileStore.Folder.UPLOADS, c.imagePath())
                    .orElseThrow(() -> new IllegalStateException("Capture image missing: " + c.id()));
            templates.addFromEnrolment(
                    session.employeeId(),
                    c.embeddingEnc(),
                    q.path("model").asString(null),
                    q.path("dim").asInt(),
                    q.path("score").isNumber() ? q.path("score").asInt() : null,
                    q.path("eye_px").isNumber() ? q.path("eye_px").asInt() : null,
                    c.pose() == null ? "FRONT" : c.pose(),
                    live ? "LIVE" : "UPLOAD",
                    image,
                    actor.employeeId());
        }
        int total = templateStore.activeCount(session.employeeId());
        String detail = chosen.size() + " templates · " + (live ? "live camera" : "upload")
                + (live && rules.livenessRequired() ? " · liveness passed" : "");
        boolean trackingChanged = faces.enrolled(session.employeeId(), before, total, detail, actor);
        end(session, "COMPLETED");
        return new EnrolmentViews.Completed(chosen.size(), total, trackingChanged);
    }

    @Transactional
    public void cancel(String code) {
        end(open(code), "CANCELLED");
    }

    /** A System Administrator lifts a liveness lockout early (settings.roles). */
    @Transactional
    public void unlock(UUID employeeId) {
        var card = employees.cards(List.of(employeeId)).get(employeeId);
        if (card == null) {
            throw new Errors.NotFound("Employee not found.");
        }
        if (store.unlock(employeeId, time.now())) {
            audit.record(
                    AuditCategory.ENROLMENT,
                    "Unlocked enrolment after liveness lockout",
                    card.displayName(),
                    null,
                    null,
                    null);
        }
    }

    /** Open sessions past their time expire; their captures go (every five minutes). */
    @Transactional
    public int expire() {
        List<EnrolmentStore.Session> expired = store.expired(time.now());
        expired.forEach(s -> end(s, "EXPIRED"));
        return expired.size();
    }

    private void end(EnrolmentStore.Session session, String status) {
        store.captures(session.id()).forEach(c -> deleteAfterCommit(c.imagePath()));
        store.deleteCaptures(session.id());
        store.finish(session.id(), status, time.now());
    }

    private EnrolmentStore.Session open(String code) {
        var session = store.lock(code).orElseThrow(() -> new Errors.NotFound("Enrolment session not found."));
        if (!"OPEN".equals(session.status())) {
            throw new Errors.Conflict("SESSION_CLOSED", "This enrolment session has ended. Start a new one.");
        }
        if (session.expiresAt().isBefore(time.now())) {
            throw new Errors.Conflict("SESSION_EXPIRED", "This enrolment session expired. Start a new one.");
        }
        return session;
    }

    private void lockedCheck(UUID employeeId, Instant now) {
        store.lockedUntil(employeeId, now).ifPresent(until -> {
            throw new Errors.Conflict(
                    "ENROLMENT_LOCKED",
                    "Enrolment is locked until "
                            + TimeFormat.of(settings.general().timeFormat())
                                    .time(time.zoned(until).toLocalTime())
                            + " after repeated liveness failures.");
        });
    }

    /** Consent GIVEN for the active notice must be on file before a capture is stored. */
    private void consentCheck(UUID employeeId) {
        int active = notices.active().version();
        boolean given = consents.current(employeeId)
                .filter(c ->
                        "GIVEN".equals(c.status()) && Integer.valueOf(active).equals(c.noticeVersion()))
                .isPresent();
        if (!given) {
            throw new Errors.Conflict(
                    "CONSENT_REQUIRED", "Record consent for notice v" + active + " before capturing face images.");
        }
    }

    /** The prototype's texts for what the worker found wrong with an image. */
    private static List<String> reasons(WorkerClient.FaceEmbedding face, boolean live, EnrolmentSettings rules) {
        List<String> reasons = new ArrayList<>();
        if (face.faces() == 0) {
            reasons.add("No face found — use a clear, front-facing photo");
            return reasons;
        }
        if (face.faces() > 1) {
            reasons.add("More than one face — crop the photo to one person");
            return reasons;
        }
        WorkerClient.FaceQuality q = face.quality();
        if (q == null) {
            reasons.add("Couldn’t read this image");
            return reasons;
        }
        if (q.eyePx() == null || q.eyePx() < rules.minEyePx()) {
            reasons.add("Face too small (" + (q.eyePx() == null ? 0 : q.eyePx()) + " px between the eyes; needs "
                    + rules.minEyePx() + ") — use a closer photo");
        }
        if (q.score() == null || q.score() < rules.minQuality()) {
            reasons.add("Quality below minimum (" + (q.score() == null ? 0 : q.score()) + "; needs "
                    + rules.minQuality() + ")");
        }
        if (!live) {
            if (q.brightness() != null && q.brightness() < TOO_DARK) {
                reasons.add("Too dark — use a brighter photo");
            }
            if (q.brightness() != null && q.brightness() > TOO_BRIGHT) {
                reasons.add("Overexposed — use a photo with less glare");
            }
            if (q.yaw() != null && Math.abs(q.yaw()) > MAX_UPLOAD_YAW) {
                reasons.add("Head turned too far — use a front-facing photo");
            }
        }
        return reasons;
    }

    private EnrolmentViews.QueueRow row(EmployeeDirectory.Card c, Instant now) {
        return new EnrolmentViews.QueueRow(
                person(c),
                c.faceStatus(),
                c.tracked(),
                store.lockedUntil(c.id(), now).orElse(null),
                c.accountEnabled());
    }

    /** Liveness failures within the lock window (the prototype's "Attempts in the last 3 min"). */
    private int failures(UUID employeeId, Instant now) {
        return store.livenessFailuresSince(
                employeeId, now.minus(Duration.ofMinutes(settings.enrolment().lockWindowMin())));
    }

    /** "High", "Medium" or "Low". */
    private static String bandLabel(String band) {
        return band == null ? "" : band.charAt(0) + band.substring(1).toLowerCase(Locale.ROOT);
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A liveness failure counts towards the lockout (R15): five in three minutes lock enrolment, with NR-02. */
    private int livenessFailure(EnrolmentStore.Session session, EnrolmentSettings rules, Instant now) {
        AttendPrincipal me = currentUser.require();
        store.livenessFailure(session.employeeId(), session.id(), me.employeeId(), now);
        int attempts =
                store.livenessFailuresSince(session.employeeId(), now.minus(Duration.ofMinutes(rules.lockWindowMin())));
        String name = employees
                .cards(List.of(session.employeeId()))
                .get(session.employeeId())
                .displayName();
        audit.record(
                AuditCategory.ENROLMENT,
                "Liveness check failed during enrolment",
                name,
                null,
                "Attempt " + attempts + " of " + rules.lockFailures(),
                null);
        if (attempts >= rules.lockFailures()) {
            Instant until = now.plus(Duration.ofMinutes(rules.lockMinutes()));
            store.lock(
                    session.employeeId(), until, attempts + " liveness failures in " + rules.lockWindowMin() + " min");
            audit.record(
                    AuditCategory.ENROLMENT,
                    "Enrolment locked after liveness failures",
                    name,
                    null,
                    "Locked " + rules.lockMinutes() + " min",
                    null);
            notifications.publish(
                    "NR-02",
                    new NotificationRequest(
                            "Enrolment locked after liveness failures",
                            name + " failed the liveness check " + attempts + " times during enrolment",
                            "/employees/" + session.employeeId(),
                            "NR-02:lock:" + session.employeeId() + ":" + until,
                            Map.of("employee", name, "attempts", attempts)));
        }
        return attempts;
    }

    private List<EnrolmentViews.Lookalike> lookalikes(UUID employeeId, List<EnrolmentStore.Capture> captures) {
        List<byte[]> embeddings =
                captures.stream().map(c -> crypto.decrypt(c.embeddingEnc())).toList();
        var found = worker.duplicates(
                embeddings, employeeId.toString(), settings.enrolment().dupThreshold());
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (var match : found) {
            try {
                scores.putIfAbsent(UUID.fromString(match.employeeId()), match.score());
            } catch (IllegalArgumentException e) {
                // A match without a valid id is not shown.
            }
        }
        var cards = employees.cards(scores.keySet());
        var bands = recognition.confidenceBands();
        boolean showScores = currentUser.has(Permission.SETTINGS_RECOGNITION);
        boolean sensitive = currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE);
        List<EnrolmentViews.Lookalike> lookalikes = new ArrayList<>();
        scores.forEach((id, score) -> {
            var card = cards.get(id);
            if (card != null) {
                BigDecimal value = BigDecimal.valueOf(score);
                lookalikes.add(new EnrolmentViews.Lookalike(
                        id,
                        card.displayName(),
                        card.employeeNumber(),
                        card.department(),
                        bands.band(value),
                        showScores ? value : null,
                        sensitive
                                ? templateStore.active(id).stream()
                                        .filter(TemplateStore.Active::hasThumbnail)
                                        .findFirst()
                                        .map(t -> FaceProfileService.thumbnailUrl(id, t.id()))
                                        .orElse(null)
                                : null));
            }
        });
        return lookalikes;
    }

    private EnrolmentViews.Session view(EnrolmentStore.Session session) {
        var card = employees.cards(List.of(session.employeeId())).get(session.employeeId());
        Instant now = time.now();
        var captures = store.captures(session.id());
        return new EnrolmentViews.Session(
                session.code(),
                person(card),
                session.method(),
                session.status(),
                session.expiresAt(),
                card.faceStatus(),
                templateStore.activeCount(session.employeeId()),
                consent(session.employeeId()),
                notices.active().version(),
                store.lockedUntil(session.employeeId(), now).orElse(null),
                failures(session.employeeId(), now),
                settings.enrolment().minGood(),
                (int) captures.stream().filter(EnrolmentStore.Capture::accepted).count(),
                captures.stream().map(this::capture).toList());
    }

    /** The current consent; on file when GIVEN for the active notice. */
    private EnrolmentViews.Consent consent(UUID employeeId) {
        int active = notices.active().version();
        return consents.current(employeeId)
                .map(c -> new EnrolmentViews.Consent(
                        c.status(),
                        c.method(),
                        c.noticeVersion(),
                        c.givenOn(),
                        c.recordedBy(),
                        "GIVEN".equals(c.status()) && Integer.valueOf(active).equals(c.noticeVersion())))
                .orElse(new EnrolmentViews.Consent("NONE", null, null, null, null, false));
    }

    private EnrolmentViews.Capture capture(EnrolmentStore.Capture c) {
        JsonNode q = c.quality() == null ? null : json.readTree(c.quality());
        return new EnrolmentViews.Capture(
                c.id(),
                c.pose(),
                c.accepted(),
                c.rejectReason(),
                q == null || !q.isObject()
                        ? null
                        : new EnrolmentViews.Quality(
                                integer(q, "eye_px"),
                                number(q, "yaw"),
                                number(q, "pitch"),
                                integer(q, "sharp"),
                                integer(q, "brightness"),
                                integer(q, "score")),
                c.livenessPassed(),
                c.createdAt());
    }

    /** The measurement as stored with the capture (model and dimension included for the template). */
    private String qualityJson(WorkerClient.FaceEmbedding face) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (face.quality() != null) {
            q.put("eye_px", face.quality().eyePx());
            q.put("yaw", face.quality().yaw());
            q.put("pitch", face.quality().pitch());
            q.put("sharp", face.quality().sharp());
            q.put("brightness", face.quality().brightness());
            q.put("score", face.quality().score());
        }
        q.put("faces", face.faces());
        q.put("model", face.model());
        q.put("dim", face.dim());
        return json.writeValueAsString(q);
    }

    /** Checked by its magic bytes, decoded within size limits and re-encoded as JPEG without metadata. */
    private static byte[] reEncode(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw invalid("image", "Choose an image.");
        }
        if (upload.length > MAX_IMAGE_BYTES) {
            throw invalid("image", "The image must be 4 MB or smaller.");
        }
        Images.Format format = Images.Format.of(upload).orElseThrow(() -> invalid("image", "Use a JPEG or PNG image."));
        try {
            return Images.encode(Images.decode(upload, format, MIN_SIDE, MAX_SIDE), Images.Format.JPEG, JPEG_QUALITY);
        } catch (Images.ImageException e) {
            throw invalid(
                    "image",
                    e.problem() == Images.Problem.SIZE
                            ? "The image must be between " + MIN_SIDE + " and " + MAX_SIDE + " pixels wide and high."
                            : "Couldn’t read this image");
        }
    }

    /** The signed form: PDF, JPEG or PNG by its magic bytes, at most 5 MB, stored encrypted. */
    private ConsentService.Document storeDocument(UUID employeeId, UploadedDocument document) {
        byte[] content = document.content();
        if (content.length == 0 || content.length > MAX_DOCUMENT_BYTES) {
            throw invalid("document", "The signed form must be a file of at most 5 MB.");
        }
        String mime;
        String extension;
        if (startsWith(content, PDF)) {
            mime = "application/pdf";
            extension = "pdf";
        } else {
            Images.Format format =
                    Images.Format.of(content).orElseThrow(() -> invalid("document", "Use a PDF, JPEG or PNG file."));
            mime = format.contentType();
            extension = format == Images.Format.PNG ? "png" : "jpg";
        }
        String path = "consent/" + employeeId + "/" + UUID.randomUUID() + "." + extension;
        files.write(FileStore.Folder.UPLOADS, path, crypto.encrypt(content));
        AfterCommit.onRollback(() -> files.delete(FileStore.Folder.UPLOADS, path));
        return new ConsentService.Document(path, mime, sha256(content));
    }

    private void deleteAfterCommit(String path) {
        if (path != null) {
            AfterCommit.run(() -> files.delete(FileStore.Folder.UPLOADS, path));
        }
    }

    private AuditActor actor() {
        AttendPrincipal me = currentUser.require();
        return AuditActor.employee(me.employeeId(), me.displayName());
    }

    private static EnrolmentViews.PersonRef person(EmployeeDirectory.Card c) {
        return new EnrolmentViews.PersonRef(
                c.id(),
                c.displayName(),
                c.employeeNumber(),
                c.department(),
                c.designation(),
                c.location(),
                c.locationHasCamera());
    }

    /** The prototype's consent status labels (the decline audit's "before"). */
    private static String label(String status) {
        return switch (status) {
            case "GIVEN" -> "Given";
            case "PENDING" -> "Pending";
            case "DECLINED" -> "Declined";
            case "WITHDRAWN" -> "Withdrawn";
            default -> "None";
        };
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 199) + "…";
    }

    private static Integer integer(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asInt() : null;
    }

    private static Double number(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    private static Errors.BusinessRule invalid(String field, String message) {
        return new Errors.BusinessRule("VALIDATION", message, List.of(new ErrorResponse.FieldError(field, message)));
    }
}
