package com.fpt.workflow.organization.repository;

import com.fpt.workflow.organization.domain.PositionAssignment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PositionAssignmentRepository extends JpaRepository<PositionAssignment, UUID> {

  /**
   * Returns the active primary assignment for an employee on a given reference date, if one exists.
   */
  @Query(
      """
      SELECT pa FROM PositionAssignment pa
      WHERE pa.employeeId = :employeeId
        AND pa.primary = true
        AND pa.status = 'ACTIVE'
        AND pa.effectiveFrom <= :referenceDate
        AND (pa.effectiveTo IS NULL OR pa.effectiveTo >= :referenceDate)
      """)
  Optional<PositionAssignment> findActivePrimaryAssignment(
      @Param("employeeId") UUID employeeId, @Param("referenceDate") LocalDate referenceDate);

  /** Returns all active assignments for an employee effective on a given date. */
  @Query(
      """
      SELECT pa FROM PositionAssignment pa
      WHERE pa.employeeId = :employeeId
        AND pa.status = 'ACTIVE'
        AND pa.effectiveFrom <= :referenceDate
        AND (pa.effectiveTo IS NULL OR pa.effectiveTo >= :referenceDate)
      """)
  List<PositionAssignment> findActiveAssignmentsForEmployee(
      @Param("employeeId") UUID employeeId, @Param("referenceDate") LocalDate referenceDate);

  /** Returns all active assignments for a position effective on a given date. */
  @Query(
      """
      SELECT pa FROM PositionAssignment pa
      WHERE pa.positionId = :positionId
        AND pa.status = 'ACTIVE'
        AND pa.effectiveFrom <= :referenceDate
        AND (pa.effectiveTo IS NULL OR pa.effectiveTo >= :referenceDate)
      ORDER BY pa.primary DESC, pa.effectiveFrom ASC
      """)
  List<PositionAssignment> findActiveAssignmentsForPosition(
      @Param("positionId") UUID positionId, @Param("referenceDate") LocalDate referenceDate);

  List<PositionAssignment> findAllByEmployeeId(UUID employeeId);
}
