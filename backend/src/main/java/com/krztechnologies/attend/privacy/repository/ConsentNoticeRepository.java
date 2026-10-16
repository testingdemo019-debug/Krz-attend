package com.krztechnologies.attend.privacy.repository;

import com.krztechnologies.attend.privacy.domain.ConsentNotice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConsentNoticeRepository extends JpaRepository<ConsentNotice, Long> {

    /** Newest version first. */
    List<ConsentNotice> findAllByOrderByNoticeVersionDesc();

    Optional<ConsentNotice> findByActiveTrue();

    @Query("SELECT coalesce(max(n.noticeVersion), 0) FROM ConsentNotice n")
    int latestVersion();
}
