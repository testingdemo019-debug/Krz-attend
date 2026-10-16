package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.crossing.repository.TimelineStore;
import com.krztechnologies.attend.rules.service.CameraMode;
import com.krztechnologies.attend.rules.service.CrossingMethod;
import com.krztechnologies.attend.rules.service.Direction;
import com.krztechnologies.attend.rules.service.PunchRulesEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The crossings of an employee's shift day as the punch rules engine sees them, and the effects it gives them (R7
 * steps 1 and 3; used by the attendance recompute).
 */
@Service
public class CrossingTimelines {

    private final TimelineStore store;

    public CrossingTimelines(TimelineStore store) {
        this.store = store;
    }

    /** The identified crossings of the day in time order, direction after flips, voids with who and why. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PunchRulesEngine.Crossing> of(UUID employeeId, LocalDate shiftDay) {
        return store.identified(employeeId, shiftDay).stream()
                .map(r -> new PunchRulesEngine.Crossing(
                        r.id(),
                        r.code(),
                        r.occurredAt(),
                        Direction.valueOf(r.direction()),
                        r.method() == null ? null : CrossingMethod.valueOf(r.method()),
                        new PunchRulesEngine.Camera(r.cameraCode(), r.cameraName(), CameraMode.valueOf(r.cameraMode())),
                        r.uTurn(),
                        r.voided() ? new PunchRulesEngine.Voided(r.voidedBy(), r.voidReason()) : null))
                .toList();
    }

    /**
     * R3 again after the employee's shift calendar changed: every identified crossing between the instants is filed
     * under {@code shiftDayOf(ts)}.
     *
     * @return the shift days that lost or gained a crossing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<LocalDate> refile(UUID employeeId, Instant from, Instant to, Function<Instant, LocalDate> shiftDayOf) {
        Set<LocalDate> touched = new TreeSet<>();
        for (TimelineStore.Filed c : store.filed(employeeId, from, to)) {
            LocalDate day = shiftDayOf.apply(c.occurredAt());
            if (!day.equals(c.shiftDay())) {
                store.setShiftDay(c.id(), day);
                touched.add(day);
                if (c.shiftDay() != null) {
                    touched.add(c.shiftDay());
                }
            }
        }
        return touched;
    }

    /** The direction before a flip correction, by crossing id (only flipped crossings of the day). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Direction> flippedFrom(UUID employeeId, LocalDate shiftDay) {
        Map<Long, Direction> flipped = new LinkedHashMap<>();
        for (TimelineStore.Row r : store.identified(employeeId, shiftDay)) {
            if (r.originalDirection() != null) {
                flipped.put(r.id(), Direction.valueOf(r.originalDirection()));
            }
        }
        return flipped;
    }

    /** Stores the effect of every crossing (R7 step 3); unchanged rows are not touched. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void storeEffects(Map<Long, PunchRulesEngine.PunchEffect> effects) {
        effects.forEach((id, effect) -> store.setEffect(id, effect.effect(), effect.reason()));
    }
}
