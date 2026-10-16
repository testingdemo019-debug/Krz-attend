package com.krztechnologies.attend.organisation.service;

import java.util.List;
import java.util.UUID;

/**
 * Employees must move into a department (department editor, member move, deactivation). Published synchronously
 * inside the organisation transaction; the employee module owns {@code attend.employment} and applies the move in the
 * same transaction, auditing "Changed department" per person. An event instead of a call, because {@code employee}
 * depends on {@code organisation} and not the reverse.
 */
public record MoveToDepartmentRequested(List<UUID> employeeIds, long departmentId, String departmentName) {

    public MoveToDepartmentRequested {
        employeeIds = List.copyOf(employeeIds);
    }
}
