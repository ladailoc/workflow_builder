package com.fpt.workflow.organization.repository;

import com.fpt.workflow.organization.domain.OrganizationUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationUnitRepository extends JpaRepository<OrganizationUnit, UUID> {

  /** Returns all descendant unit IDs (inclusive) ordered by depth. */
  @Query(
      value =
          """
          SELECT c.descendant_id
          FROM organization_unit_closure c
          WHERE c.ancestor_id = :unitId
          ORDER BY c.depth ASC
          """,
      nativeQuery = true)
  List<UUID> findAllDescendantIds(@Param("unitId") UUID unitId);

  /** Returns all ancestor unit IDs (inclusive, nearest first) ordered by depth. */
  @Query(
      value =
          """
          SELECT c.ancestor_id
          FROM organization_unit_closure c
          WHERE c.descendant_id = :unitId
          ORDER BY c.depth ASC
          """,
      nativeQuery = true)
  List<UUID> findAllAncestorIds(@Param("unitId") UUID unitId);
}
