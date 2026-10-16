package com.krztechnologies.attend.access.repository;

import com.krztechnologies.attend.access.domain.AccessRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    Optional<AccessRequest> findByCode(String code);

    Optional<AccessRequest> findFirstByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    List<AccessRequest> findAllByOrderByCreatedAtDesc();

    List<AccessRequest> findByStatusOrderByCreatedAtDesc(AccessRequest.Status status);

    long countByStatus(AccessRequest.Status status);
}
