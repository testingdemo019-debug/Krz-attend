package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.domain.Employee;
import com.krztechnologies.attend.employee.domain.Employment;
import com.krztechnologies.attend.employee.repository.EmployeeRepository;
import com.krztechnologies.attend.employee.repository.EmploymentRepository;
import com.krztechnologies.attend.organisation.service.MoveToDepartmentRequested;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves people to a department (organisation pages, Employees → bulk "Change department"), in the caller's
 * transaction: a person without employment details gets a record with only the department. Each move is audited
 * "Changed department" (Employees) with the old and new department names; people already there are skipped.
 */
@Component
public class DepartmentMembership {

    private final EmployeeRepository employees;
    private final EmploymentRepository employments;
    private final OrganisationDirectory organisation;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TimeService time;

    public DepartmentMembership(
            EmployeeRepository employees,
            EmploymentRepository employments,
            OrganisationDirectory organisation,
            AuditService audit,
            ApplicationEventPublisher events,
            TimeService time) {
        this.employees = employees;
        this.employments = employments;
        this.organisation = organisation;
        this.audit = audit;
        this.events = events;
        this.time = time;
    }

    /** A move requested by the organisation pages (department create, members, archive). */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMoveRequested(MoveToDepartmentRequested request) {
        move(request.employeeIds(), request.departmentId(), request.departmentName());
    }

    /** @return how many people actually moved */
    @Transactional(propagation = Propagation.MANDATORY)
    public int move(Collection<UUID> employeeIds, long departmentId, String departmentName) {
        List<UUID> moved = new ArrayList<>();
        for (Employee person : employees.findAllById(employeeIds).stream()
                .sorted(Comparator.comparing(Employee::getDisplayName))
                .toList()) {
            Employment employment =
                    employments.findById(person.getId()).orElseGet(() -> new Employment(person.getId()));
            Long before = employment.getDepartmentId();
            if (Long.valueOf(departmentId).equals(before)) {
                continue;
            }
            employment.setDepartmentId(departmentId);
            employments.save(employment);
            audit.record(
                    AuditCategory.EMPLOYEES,
                    "Changed department",
                    person.getDisplayName(),
                    before == null
                            ? "—"
                            : organisation
                                    .department(before)
                                    .map(OrganisationDirectory.DepartmentInfo::name)
                                    .orElse("—"),
                    departmentName,
                    null);
            moved.add(person.getId());
        }
        if (!moved.isEmpty()) {
            employments.flush();
            events.publishEvent(AttendanceAffected.employees(moved, time.today(), time.today()));
        }
        return moved.size();
    }
}
