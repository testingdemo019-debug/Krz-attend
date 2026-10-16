package com.krztechnologies.attend.employee.repository;

import com.krztechnologies.attend.employee.domain.FaceProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceProfileRepository extends JpaRepository<FaceProfile, UUID> {}
