package com.krztechnologies.attend.organisation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The rules of a shift from {@code effectiveFrom} on (R10): past days keep the version they had. Saving rules for a day
 * that already has a version replaces that version's values (one version per shift and day).
 */
@Entity
@Table(name = "shift_version")
public class ShiftVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_id", nullable = false, updatable = false)
    private Long shiftId;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "break_minutes", nullable = false)
    private short breakMinutes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "weekly_offs", nullable = false, columnDefinition = "smallint[]")
    private short[] weeklyOffs;

    @Column(name = "late_grace_min", nullable = false)
    private short lateGraceMinutes;

    @Column(name = "early_grace_min", nullable = false)
    private short earlyGraceMinutes;

    @Column(name = "half_day_below_hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal halfDayBelowHours;

    @Column(name = "absent_below_hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal absentBelowHours;

    @Column(name = "window_before_hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal windowBeforeHours;

    @Column(name = "day_close_hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal dayCloseHours;

    @Column(name = "note")
    private String note;

    @Column(name = "edited_by")
    private UUID editedBy;

    @Column(name = "edited_by_label")
    private String editedByLabel;

    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;

    protected ShiftVersion() {}

    public ShiftVersion(Long shiftId, LocalDate effectiveFrom) {
        this.shiftId = shiftId;
        this.effectiveFrom = effectiveFrom;
    }

    /** Sets the rules and who changed them, and why. */
    public void apply(ShiftRules rules, String newNote, UUID by, String byLabel, Instant at) {
        this.startTime = rules.start();
        this.endTime = rules.end();
        this.breakMinutes = (short) rules.breakMinutes();
        this.weeklyOffs = new short[rules.weeklyOffs().size()];
        for (int i = 0; i < weeklyOffs.length; i++) {
            weeklyOffs[i] = rules.weeklyOffs().get(i).shortValue();
        }
        this.lateGraceMinutes = (short) rules.lateGraceMinutes();
        this.earlyGraceMinutes = (short) rules.earlyGraceMinutes();
        this.halfDayBelowHours = rules.halfDayBelowHours();
        this.absentBelowHours = rules.absentBelowHours();
        this.windowBeforeHours = rules.windowBeforeHours();
        this.dayCloseHours = rules.dayCloseHours();
        this.note = newNote;
        this.editedBy = by;
        this.editedByLabel = byLabel;
        this.editedAt = at;
    }

    public ShiftRules rules() {
        List<Integer> offs = new ArrayList<>();
        for (short day : weeklyOffs) {
            offs.add((int) day);
        }
        return new ShiftRules(
                startTime,
                endTime,
                breakMinutes,
                offs,
                lateGraceMinutes,
                earlyGraceMinutes,
                halfDayBelowHours,
                absentBelowHours,
                windowBeforeHours,
                dayCloseHours);
    }

    public Long getId() {
        return id;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getNote() {
        return note;
    }

    public String getEditedByLabel() {
        return editedByLabel;
    }

    public Instant getEditedAt() {
        return editedAt;
    }
}
