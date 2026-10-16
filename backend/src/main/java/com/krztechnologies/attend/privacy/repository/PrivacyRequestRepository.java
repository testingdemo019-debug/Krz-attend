package com.krztechnologies.attend.privacy.repository;

import com.krztechnologies.attend.privacy.domain.PrivacyRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyRequestRepository extends JpaRepository<PrivacyRequest, Long> {

    Optional<PrivacyRequest> findByCode(String code);

    List<PrivacyRequest> findAllByOrderByReceivedOnDescIdDesc();

    List<PrivacyRequest> findByStatusOrderByReceivedOnDescIdDesc(PrivacyRequest.Status status);
}
