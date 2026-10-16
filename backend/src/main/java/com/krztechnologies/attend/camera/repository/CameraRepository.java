package com.krztechnologies.attend.camera.repository;

import com.krztechnologies.attend.camera.domain.Camera;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    Optional<Camera> findByCode(String code);

    List<Camera> findAllByOrderByCodeAsc();

    List<Camera> findAllByEnabledTrueOrderByCodeAsc();
}
