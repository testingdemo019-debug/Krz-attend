package com.krztechnologies.attend.recognition.repository;

import com.krztechnologies.attend.recognition.domain.ThresholdProfile;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ThresholdProfileRepository extends JpaRepository<ThresholdProfile, Long> {

    Optional<ThresholdProfile> findByActiveTrue();

    /** The active profile, locked, so two saves or switches are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ThresholdProfile> findForUpdateByActiveTrue();

    Optional<ThresholdProfile> findByCode(String code);

    List<ThresholdProfile> findAllByOrderByIdDesc();
}
