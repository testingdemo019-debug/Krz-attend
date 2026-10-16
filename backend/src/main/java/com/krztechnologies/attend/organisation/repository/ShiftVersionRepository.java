package com.krztechnologies.attend.organisation.repository;

import com.krztechnologies.attend.organisation.domain.ShiftVersion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftVersionRepository extends JpaRepository<ShiftVersion, Long> {

    List<ShiftVersion> findByShiftIdOrderByEffectiveFromAsc(Long shiftId);

    Optional<ShiftVersion> findByShiftIdAndEffectiveFrom(Long shiftId, LocalDate effectiveFrom);
}
