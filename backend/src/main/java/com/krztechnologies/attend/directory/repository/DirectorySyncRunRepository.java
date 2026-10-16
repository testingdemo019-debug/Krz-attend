package com.krztechnologies.attend.directory.repository;

import com.krztechnologies.attend.directory.domain.DirectorySyncRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectorySyncRunRepository extends JpaRepository<DirectorySyncRun, Long> {

    List<DirectorySyncRun> findTop50ByOrderByStartedAtDesc();

    Optional<DirectorySyncRun> findFirstByOrderByStartedAtDesc();
}
