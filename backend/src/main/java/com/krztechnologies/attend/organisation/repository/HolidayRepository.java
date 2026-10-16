package com.krztechnologies.attend.organisation.repository;

import com.krztechnologies.attend.organisation.domain.Holiday;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    Optional<Holiday> findByCode(String code);
}
