package com.krztechnologies.attend.organisation.repository;

import com.krztechnologies.attend.organisation.domain.Shift;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByCode(String code);

    Optional<Shift> findByIsDefaultTrue();

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCase(String name);
}
