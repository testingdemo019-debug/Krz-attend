package com.krztechnologies.attend.camera.repository;

import com.krztechnologies.attend.camera.domain.CalibrationRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationRunRepository extends JpaRepository<CalibrationRun, Long> {

    Optional<CalibrationRun> findByIdAndCameraId(Long id, Long cameraId);
}
