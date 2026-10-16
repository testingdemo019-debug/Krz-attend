package com.krztechnologies.attend.organisation.repository;

import com.krztechnologies.attend.organisation.domain.ShiftAllocation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftAllocationRepository extends JpaRepository<ShiftAllocation, Long> {

    Optional<ShiftAllocation> findByCode(String code);

    List<ShiftAllocation> findByDepartmentId(Long departmentId);

    boolean existsByDepartmentId(Long departmentId);
}
