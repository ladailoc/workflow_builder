package com.fpt.workflow.organization.repository;

import com.fpt.workflow.organization.domain.Position;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PositionRepository extends JpaRepository<Position, UUID> {

  /** Returns the nearest ancestor of positionId at depth=1 (the direct manager position). */
  @Query(
      value =
          """
          SELECT c.ancestor_id
          FROM position_closure c
          WHERE c.descendant_id = :positionId
            AND c.depth = 1
          """,
      nativeQuery = true)
  Optional<UUID> findDirectManagerPositionId(@Param("positionId") UUID positionId);

  /**
   * Returns all ancestor position IDs of positionId ordered by depth (nearest first). Includes
   * depth=0 (self).
   */
  @Query(
      value =
          """
          SELECT c.ancestor_id
          FROM position_closure c
          WHERE c.descendant_id = :positionId
          ORDER BY c.depth ASC
          """,
      nativeQuery = true)
  List<UUID> findAllAncestorIds(@Param("positionId") UUID positionId);

  /** Returns all descendant position IDs (inclusive) ordered by depth. */
  @Query(
      value =
          """
          SELECT c.descendant_id
          FROM position_closure c
          WHERE c.ancestor_id = :positionId
          ORDER BY c.depth ASC
          """,
      nativeQuery = true)
  List<UUID> findAllDescendantIds(@Param("positionId") UUID positionId);

  /** Returns positions in an org unit that are marked as head of unit and active. */
  @Query(
      """
      SELECT p FROM Position p
      WHERE p.orgUnitId = :orgUnitId
        AND p.headOfUnit = true
        AND p.status = 'ACTIVE'
      """)
  List<Position> findActiveHeadsOfUnit(@Param("orgUnitId") UUID orgUnitId);

  Optional<Position> findByPositionCode(String positionCode);
}
