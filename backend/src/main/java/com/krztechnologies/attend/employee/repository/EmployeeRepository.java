package com.krztechnologies.attend.employee.repository;

import com.krztechnologies.attend.employee.domain.Employee;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByTenantIdAndObjectId(UUID tenantId, UUID objectId);

    @Query("SELECT e.objectId FROM Employee e WHERE e.tenantId = :tenantId AND e.accountEnabled = true")
    List<UUID> findEnabledObjectIds(UUID tenantId);

    long countByAccountEnabledTrue();
}
