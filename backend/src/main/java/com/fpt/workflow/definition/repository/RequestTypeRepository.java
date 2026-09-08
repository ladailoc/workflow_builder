package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.RequestType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestTypeRepository extends JpaRepository<RequestType, UUID> {

  Optional<RequestType> findByKey(String key);

  boolean existsByKey(String key);

  List<RequestType> findAllByActiveTrueOrderByCategoryAscNameAsc();
}
