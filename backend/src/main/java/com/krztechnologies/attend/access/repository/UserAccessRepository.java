package com.krztechnologies.attend.access.repository;

import com.krztechnologies.attend.access.domain.UserAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccessRepository extends JpaRepository<UserAccess, UUID> {

    @Query("SELECT DISTINCT a FROM UserAccess a JOIN a.roles r WHERE r.code = :roleCode")
    List<UserAccess> findAllWithRole(String roleCode);

    @Query("SELECT count(DISTINCT a) FROM UserAccess a JOIN a.roles r WHERE r.id = :roleId")
    long countWithRole(Long roleId);

    /** Users per role id, in one query (the roles list). Roles without users are missing. */
    @Query("SELECT r.id, count(DISTINCT a) FROM UserAccess a JOIN a.roles r GROUP BY r.id")
    List<Object[]> countPerRole();

    /** The employee's account flag (read-only; the employee module owns the table). Empty if unknown. */
    @Query(value = "SELECT account_enabled FROM attend.employee WHERE id = :employeeId", nativeQuery = true)
    Optional<Boolean> isAccountEnabled(UUID employeeId);
}
