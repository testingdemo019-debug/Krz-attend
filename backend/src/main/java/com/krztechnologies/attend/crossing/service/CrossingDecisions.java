package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.crossing.repository.DecisionStore;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.Decision;
import com.krztechnologies.attend.rules.service.PunchRulesEngine;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The crossing side of review decisions (R12), in the caller's transaction: give the crossings of an item an identity
 * (method REVIEW, the shift day of R3), record a decision without identity, or put back what a decision changed. Every
 * (employee, shift day) that gains or loses a crossing is recomputed ({@link CrossingsChanged}).
 */
@Service
public class CrossingDecisions {

    private final DecisionStore store;
    private final EmployeeDirectory employees;
    private final OrganisationDirectory organisation;
    private final SettingsService settings;
    private final TimeService time;
    private final ApplicationEventPublisher events;

    public CrossingDecisions(
            DecisionStore store,
            EmployeeDirectory employees,
            OrganisationDirectory organisation,
            SettingsService settings,
            TimeService time,
            ApplicationEventPublisher events) {
        this.store = store;
        this.employees = employees;
        this.organisation = organisation;
        this.settings = settings;
        this.time = time;
        this.events = events;
    }

    /**
     * A crossing of a review item.
     *
     * @param quality the best face frame's quality JSON ({@code eye_px}, {@code yaw}, {@code pitch}, {@code sharp},
     *     {@code quality}, {@code light}), or null
     * @param candidates the worker's top candidates JSON ({@code [{employee_id, score}]}), or null
     * @param faceCrop the encrypted face crop under {@code crops/}, or null
     * @param bodyCrop the encrypted body crop under {@code crops/}, or null
     * @param margin the gap between the top two face candidates, or null
     */
    public record Sighting(
            long id,
            long reviewItemId,
            String code,
            UUID crossingUid,
            Instant occurredAt,
            String direction,
            boolean uTurn,
            UUID employeeId,
            String method,
            LocalDate shiftDay,
            String decision,
            UUID resolvedBy,
            Instant resolvedAt,
            String effect,
            String effectReason,
            String quality,
            String candidates,
            String faceCrop,
            boolean cropsPurged,
            String cameraCode,
            String cameraName,
            Integer trackId,
            String bodyCrop,
            String liveness,
            BigDecimal margin,
            BigDecimal reidScore) {}

    /** The item's crossings in time order, locked until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Sighting> ofItem(long reviewItemId) {
        return store.ofItem(reviewItemId).stream()
                .map(CrossingDecisions::sighting)
                .toList();
    }

    /** An employee's IN crossing (a back-view exit's "Came in"). */
    public record InCrossing(String code, Instant occurredAt, boolean faceCrop) {}

    /** The employee's last IN crossing in [{@code since}, {@code before}) (a back-view exit's "Came in"). */
    @Transactional(readOnly = true)
    public Optional<InCrossing> lastIn(UUID employeeId, Instant since, Instant before) {
        return store.lastIn(employeeId, since, before).map(c -> new InCrossing(c.code(), c.occurredAt(), c.faceCrop()));
    }

    /** The crossings of these items, by item, in time order (the queue). */
    @Transactional(readOnly = true)
    public Map<Long, List<Sighting>> ofItems(Collection<Long> reviewItemIds) {
        Map<Long, List<Sighting>> byItem = new LinkedHashMap<>();
        for (DecisionStore.Row r : store.ofItems(reviewItemIds)) {
            byItem.computeIfAbsent(r.reviewItemId(), k -> new ArrayList<>()).add(sighting(r));
        }
        return byItem;
    }

    /** The crossings become the employee's (CONFIRM, ASSIGN, FALSE_ALARM). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void identify(Collection<Sighting> crossings, UUID employeeId, UUID by) {
        var profile = employees.attendanceProfiles(List.of(employeeId)).get(employeeId);
        ShiftCalendar calendar = organisation.calendar();
        var person = new ShiftCalendar.Person(profile.id(), profile.departmentId(), profile.locationId());
        Instant now = time.now();
        Set<CrossingsChanged> changed = new LinkedHashSet<>(previous(crossings));
        for (Sighting c : crossings) {
            LocalDate day = calendar.shiftDayOf(person, c.occurredAt());
            store.identify(c.id(), employeeId, day, by, now);
            changed.add(new CrossingsChanged(employeeId, day));
        }
        changed.forEach(events::publishEvent);
    }

    /** No punch: not an employee, rejected or a confirmed spoof. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void decide(Collection<Sighting> crossings, Decision decision, UUID by) {
        var effect = PunchRulesEngine.unidentified(
                false, decision, null, settings.privacy().unknownHours());
        Instant now = time.now();
        for (Sighting c : crossings) {
            store.decide(c.id(), decision.name(), by, now, effect.effect().name(), effect.reason());
        }
        previous(crossings).forEach(events::publishEvent);
    }

    /** Puts the crossings back as they were before a decision (undo). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(Collection<Sighting> before, Collection<Sighting> now) {
        Set<CrossingsChanged> changed = new LinkedHashSet<>(previous(now));
        for (Sighting c : before) {
            store.restore(row(c));
        }
        changed.addAll(previous(before));
        changed.forEach(events::publishEvent);
    }

    /** The days these crossings belong to now (they lose them). */
    private static Set<CrossingsChanged> previous(Collection<Sighting> crossings) {
        Set<CrossingsChanged> days = new LinkedHashSet<>();
        for (Sighting c : crossings) {
            if (c.employeeId() != null && c.shiftDay() != null) {
                days.add(new CrossingsChanged(c.employeeId(), c.shiftDay()));
            }
        }
        return days;
    }

    private static Sighting sighting(DecisionStore.Row r) {
        return new Sighting(
                r.id(),
                r.reviewItemId(),
                r.code(),
                r.crossingUid(),
                r.occurredAt(),
                r.direction(),
                r.uTurn(),
                r.employeeId(),
                r.method(),
                r.shiftDay(),
                r.decision(),
                r.resolvedBy(),
                r.resolvedAt(),
                r.effect(),
                r.effectReason(),
                r.quality(),
                r.candidates(),
                r.faceCrop(),
                r.cropsPurged(),
                r.cameraCode(),
                r.cameraName(),
                r.trackId(),
                r.bodyCrop(),
                r.liveness(),
                r.margin(),
                r.reidScore());
    }

    private static DecisionStore.Row row(Sighting s) {
        return new DecisionStore.Row(
                s.id(),
                s.reviewItemId(),
                s.code(),
                s.crossingUid(),
                s.occurredAt(),
                s.direction(),
                s.uTurn(),
                s.employeeId(),
                s.method(),
                s.shiftDay(),
                s.decision(),
                s.resolvedBy(),
                s.resolvedAt(),
                s.effect(),
                s.effectReason(),
                s.quality(),
                s.candidates(),
                s.faceCrop(),
                s.cropsPurged(),
                s.cameraCode(),
                s.cameraName(),
                s.trackId(),
                s.bodyCrop(),
                s.liveness(),
                s.margin(),
                s.reidScore());
    }
}
