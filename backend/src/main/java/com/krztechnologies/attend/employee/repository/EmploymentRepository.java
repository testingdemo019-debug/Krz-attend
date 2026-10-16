package com.krztechnologies.attend.employee.repository;

import com.krztechnologies.attend.employee.domain.Employment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmploymentRepository extends JpaRepository<Employment, UUID> {

    /** The holder of an HRMS Employee ID (unique), ignoring case. */
    Optional<Employment> findByHrmsEmployeeIdIgnoreCase(String hrmsEmployeeId);
}
