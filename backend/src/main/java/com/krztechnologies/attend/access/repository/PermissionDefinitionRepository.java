package com.krztechnologies.attend.access.repository;

import com.krztechnologies.attend.access.domain.PermissionDefinition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, String> {

    List<PermissionDefinition> findAllByOrderBySortOrderAsc();
}
