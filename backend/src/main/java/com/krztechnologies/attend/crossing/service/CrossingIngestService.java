package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.common.realtime.SseHub;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.crossing.repository.IngestStore;
import com.krztechnologies.attend.crossing.service.CrossingMessage.Hint;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeDirectory.AttendanceProfile;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import com.krztechnologies.attend.notification.service.RuleSettings;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.Decision;
import com.krztechnologies.attend.rules.service.Direction;
import com.krztechnologies.attend.rules.service.PunchRulesEngine;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores one crossing message in one transaction (Redis contract §1): validates the camera and the identity (an AUTO
 * employee must exist with an active face profile, else the crossing is UNKNOWN), maps the message to the row exactly
 * as the contract's table says (an uncertain candidate is never stored as the identity), assigns the shift day (R3),
 * creates or joins the review item, raises NR-02 and NR-05, and has the day recomputed ({@link CrossingsChanged}).
 * A walk during an enrolment capture (D-118) is stored as recorded only, with no review item or notification.
 * A redelivered message ({@code crossing_uid} already stored) changes nothing.
 */
@Service
public class CrossingIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(CrossingIngestService.class);

    private final IngestStore store;
    private final EmployeeDirectory employees;
    private final OrganisationDirectory organisation;
    private final NotificationService notifications;
    private final RuleSettings rules;
    private final SettingsService settings;
    private final TimeService time;
    private final ApplicationEventPublisher events;
    private final SseHub realtime;

    public CrossingIngestService(
            IngestStore store,
            EmployeeDirectory employees,
            OrganisationDirectory organisation,
            NotificationService notifications,
            RuleSettings rules,
            SettingsService settings,
            TimeService time,
            ApplicationEventPublisher events,
            SseHub realtime) {
        this.store = store;
        this.employees = employees;
        this.organisation = organisation;
        this.notifications = notifications;
        this.rules = rules;
        this.settings = settings;
        this.time = time;
        this.events = events;
        this.realtime = realtime;
    }

    /** What happened to a message. */
    public enum Outcome {
        STORED,
        DUPLICATE
    }

    /**
     * @throws CrossingMessage.Invalid when the camera is unknown (poison)
     */
    @Transactional
    public Outcome ingest(CrossingMessage m) {
        IngestStore.CameraRef camera = store.camera(m.cameraCode())
                .orElseThrow(() -> new CrossingMessage.Invalid("Unknown camera " + m.cameraCode()));
        Hint hint = m.hint();
        AttendanceProfile person = null;
        if (hint == Hint.AUTO) {
            person = employees.attendanceProfiles(List.of(m.employeeId())).get(m.employeeId());
            if (person == null || !person.recognisable()) {
                LOG.warn(
                        "Crossing {} named an employee who cannot be matched; stored as UNKNOWN (trace {})",
                        m.crossingUid(),
                        m.traceId());
                person = null;
                hint = Hint.UNKNOWN;
            }
        }
        boolean identified = person != null;
        // A walk during an enrolment capture at the entry point (D-118) is recorded, never counted or reviewed.
        boolean capture = m.enrolmentCapture();
        Decision decision = m.uTurn() || identified || capture ? Decision.AUTO : Decision.PENDING_REVIEW;
        LocalDate shiftDay = identified
                ? organisation
                        .calendar()
                        .shiftDayOf(
                                new ShiftCalendar.Person(person.id(), person.departmentId(), person.locationId()),
                                m.ts())
                : null;
        boolean unknown = hint == Hint.UNKNOWN && !m.uTurn() && !capture;
        var stored = store.insert(new IngestStore.NewCrossing(
                m.crossingUid(),
                camera.id(),
                m.ts(),
                time.now(),
                m.direction().name(),
                m.uTurn(),
                identified ? m.method().name() : null,
                identified ? person.id() : null,
                shiftDay,
                decision.name(),
                m.score(),
                m.secondScore(),
                m.margin(),
                m.agreeK(),
                m.agreeN(),
                m.reidScore(),
                m.liveness(),
                m.livenessScore(),
                m.quality(),
                m.candidates(),
                m.faceCrop(),
                m.faceCropBlur(),
                m.bodyCrop(),
                m.bodyCropBlur(),
                unknown ? m.unknownLabel() : null,
                unknown ? m.unknownCluster() : null,
                m.modelVersion(),
                m.trackId(),
                m.workerId(),
                m.traceId(),
                capture));
        if (stored.isEmpty()) {
            LOG.debug("Crossing {} was stored before (redelivery)", m.crossingUid());
            return Outcome.DUPLICATE;
        }
        IngestStore.Stored crossing = stored.get();
        if (identified) {
            events.publishEvent(new CrossingsChanged(person.id(), shiftDay));
        } else {
            IngestStore.ReviewRef item = decision == Decision.PENDING_REVIEW ? review(m, hint, crossing, camera) : null;
            var effect = capture && !m.uTurn()
                    ? PunchRulesEngine.enrolmentCapture()
                    : PunchRulesEngine.unidentified(
                            m.uTurn(),
                            decision,
                            item == null ? null : item.code(),
                            settings.privacy().unknownHours());
            store.linkReview(
                    crossing.id(),
                    item == null ? null : item.id(),
                    effect.effect().name(),
                    effect.reason());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", crossing.code());
        payload.put("camera", camera.code());
        payload.put("occurredAt", m.ts().toString());
        payload.put("direction", m.direction().name());
        payload.put("decision", decision.name());
        AfterCommit.run(() -> realtime.publish("crossing.created", payload, Permission.EVENTS_VIEW));
        return Outcome.STORED;
    }

    /** Creates the review item, or joins the open item of the unknown face's cluster; NR-02 and NR-05. */
    private IngestStore.ReviewRef review(
            CrossingMessage m, Hint hint, IngestStore.Stored crossing, IngestStore.CameraRef camera) {
        String type = switch (hint) {
            case UNCERTAIN -> "UNCERTAIN";
            case UNKNOWN -> "UNKNOWN";
            case LIVENESS_FAIL -> "LIVENESS";
            case REID_UNCERTAIN -> "REID";
            case AUTO -> throw new IllegalStateException("AUTO crossings have no review item");
        };
        IngestStore.ReviewRef item;
        if (hint == Hint.UNKNOWN && m.unknownCluster() != null) {
            item = store.openUnknownItem(m.unknownCluster()).orElse(null);
            if (item != null) {
                store.sighted(item.id(), m.ts());
            } else {
                item = store.createReviewItem(type, m.unknownLabel(), m.unknownCluster(), m.ts());
            }
        } else {
            item = store.createReviewItem(type, hint == Hint.UNKNOWN ? m.unknownLabel() : null, null, m.ts());
        }
        String link = "/review?tab=" + type.toLowerCase(Locale.ROOT) + "&item=" + item.code();
        if (hint == Hint.LIVENESS_FAIL) {
            notifications.publish(
                    "NR-02",
                    new NotificationRequest(
                            "Liveness alert at " + camera.name(),
                            "Possible photo or phone spoof. Check the Review Queue.",
                            link,
                            "NR-02:" + crossing.code(),
                            Map.of("camera", camera.name(), "crossing", crossing.code())));
        }
        if (hint == Hint.UNKNOWN && m.direction() == Direction.IN && afterOfficeHours(m)) {
            String at = TimeFormat.of(settings.general().timeFormat())
                    .time(time.zoned(m.ts()).toLocalTime());
            notifications.publish(
                    "NR-05",
                    new NotificationRequest(
                            "Unknown face after office hours",
                            camera.name() + " · " + at,
                            link,
                            "NR-05:" + item.code(),
                            Map.of("camera", camera.name(), "time", at)));
        }
        events.publishEvent(new ReviewQueueChanged(item.code()));
        return item;
    }

    /** NR-05's office hours (IST, rule parameters {@code officeStart}/{@code officeEnd}). */
    private boolean afterOfficeHours(CrossingMessage m) {
        LocalTime start = LocalTime.parse(rules.param("NR-05", "officeStart", "08:00"));
        LocalTime end = LocalTime.parse(rules.param("NR-05", "officeEnd", "20:00"));
        LocalTime at = time.zoned(m.ts()).toLocalTime();
        return at.isBefore(start) || !at.isBefore(end);
    }
}
