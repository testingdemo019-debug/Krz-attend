package com.krztechnologies.attend.organisation.repository;

import com.krztechnologies.attend.organisation.domain.EntryPoint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntryPointRepository extends JpaRepository<EntryPoint, Long> {

    Optional<EntryPoint> findByCode(String code);

    List<EntryPoint> findByLocationIdOrderByCodeAsc(Long locationId);

    boolean existsByLocationIdAndNameIgnoreCase(Long locationId, String name);
}
