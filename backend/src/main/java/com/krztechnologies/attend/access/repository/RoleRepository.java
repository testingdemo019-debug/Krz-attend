package com.krztechnologies.attend.access.repository;

import com.krztechnologies.attend.access.domain.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCase(String name);
}
