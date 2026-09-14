package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.RequestType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestTypeRepository extends JpaRepository<RequestType, UUID> {

  Optional<RequestType> findByKey(String key);

  boolean existsByKey(String key);

  List<RequestType> findAllByActiveTrueOrderByCategoryAscNameAsc();

  Page<RequestType> findAllByActive(boolean active, Pageable pageable);

  @Query(
      "select requestType from RequestType requestType "
          + "where lower(requestType.name) like :pattern or lower(requestType.key) like :pattern")
  Page<RequestType> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

  @Query(
      "select requestType from RequestType requestType "
          + "where (lower(requestType.name) like :pattern or lower(requestType.key) like :pattern) "
          + "and requestType.active = :active")
  Page<RequestType> searchByPatternAndActive(
      @Param("pattern") String pattern, @Param("active") boolean active, Pageable pageable);
}
