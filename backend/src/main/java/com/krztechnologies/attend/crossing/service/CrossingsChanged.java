package com.krztechnologies.attend.crossing.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The identified crossings of one employee's shift day changed (a new crossing, a correction or a review decision, or
 * their undo). Published inside the transaction; the attendance module recomputes the day (R7) before the commit.
 */
public record CrossingsChanged(UUID employeeId, LocalDate shiftDay) {}
