package com.krztechnologies.attend.directory.repository;

import com.krztechnologies.attend.directory.domain.DirectorySyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectorySyncStateRepository extends JpaRepository<DirectorySyncState, Short> {}
