package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.crossing.repository.CorrectionStore;
import com.krztechnologies.attend.rules.service.Direction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The crossing side of corrections (R11): void and flip a crossing, and undo either, in the caller's transaction.
 * Every change is recorded in {@code correction} and has the day recomputed ({@link CrossingsChanged}). A flip toggles
 * the direction and keeps the original; flipping or undoing a flip toggles it back.
 */
@Service
public class CrossingCorrections {

    private final CorrectionStore store;
    private final ApplicationEventPublisher events;
    private final TimeService time;

    public CrossingCorrections(CorrectionStore store, ApplicationEventPublisher events, TimeService time) {
        this.store = store;
        this.events = events;
        this.time = time;
    }

    /** The two kinds of correction. */
    public enum Kind {
        VOID,
        FLIP
    }

    /**
     * A crossing a correction applies to (locked for the transaction).
     *
     * @param employeeId null for a crossing without identity (not correctable)
     */
    public record Target(
            long id,
            String code,
            UUID employeeId,
            String employeeName,
            LocalDate shiftDay,
            Direction direction,
            boolean voided,
            Instant occurredAt) {}

    /** An undone correction and the crossing it changed. */
    public record Undone(Kind kind, Target crossing) {}

    /** The crossing as it is, without a lock (previews). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Target> find(String code) {
        return store.find(code).map(CrossingCorrections::target);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Target> lock(String code) {
        return store.lock(code).map(CrossingCorrections::target);
    }

    /** @return the correction's id (what an undo reverts) */
    @Transactional(propagation = Propagation.MANDATORY)
    public long apply(Target crossing, Kind kind, String reason, UUID actorId) {
        long id = store.insert(crossing.id(), kind.name(), reason, actorId, time.now());
        change(crossing, kind);
        return id;
    }

    /** Reverts a correction once; empty when it does not exist or was undone already. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Undone> undo(long correctionId, UUID actorId) {
        var correction = store.correction(correctionId).filter(c -> !c.undone()).orElse(null);
        if (correction == null) {
            return Optional.empty();
        }
        Target crossing = store.lock(correction.crossingId())
                .map(CrossingCorrections::target)
                .orElseThrow();
        store.markUndone(correctionId, actorId, time.now());
        Kind kind = Kind.valueOf(correction.kind());
        if (kind == Kind.VOID) {
            store.setVoided(crossing.id(), store.hasActiveVoid(crossing.id()));
            publish(crossing);
        } else {
            change(crossing, Kind.FLIP);
        }
        return Optional.of(new Undone(kind, crossing));
    }

    private void change(Target crossing, Kind kind) {
        if (kind == Kind.VOID) {
            store.setVoided(crossing.id(), true);
        } else {
            var row = store.lock(crossing.id()).orElseThrow();
            if (row.originalDirection() != null) {
                store.setDirection(crossing.id(), row.originalDirection(), null);
            } else {
                store.setDirection(crossing.id(), opposite(row.direction()), row.direction());
            }
        }
        publish(crossing);
    }

    private void publish(Target crossing) {
        if (crossing.employeeId() != null && crossing.shiftDay() != null) {
            events.publishEvent(new CrossingsChanged(crossing.employeeId(), crossing.shiftDay()));
        }
    }

    private static String opposite(String direction) {
        return Direction.valueOf(direction) == Direction.IN ? Direction.OUT.name() : Direction.IN.name();
    }

    private static Target target(CorrectionStore.CrossingRow r) {
        return new Target(
                r.id(),
                r.code(),
                r.employeeId(),
                r.employeeName(),
                r.shiftDay(),
                Direction.valueOf(r.direction()),
                r.voided(),
                r.occurredAt());
    }
}
