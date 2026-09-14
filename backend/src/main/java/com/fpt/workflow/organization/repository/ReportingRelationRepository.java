package com.fpt.workflow.organization.repository;

import com.fpt.workflow.organization.domain.ReportingRelation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportingRelationRepository extends JpaRepository<ReportingRelation, UUID> {

  /**
   * Active overriding relations for a subordinate on a date, lowest priority first (then manager
   * id for determinism) — mirrors the partial unique index ordering.
   */
  @Query(
      """
      SELECT r FROM ReportingRelation r
      WHERE r.subordinateEmployeeId = :subordinateEmployeeId
        AND r.status = 'ACTIVE'
        AND r.effectiveFrom <= :on
        AND (r.effectiveTo IS NULL OR r.effectiveTo > :on)
      ORDER BY r.priority ASC, r.managerEmployeeId ASC
      """)
  List<ReportingRelation> findActiveOverrides(
      @Param("subordinateEmployeeId") UUID subordinateEmployeeId, @Param("on") LocalDate on);

  /** All active relations in which the given employee is the manager on the date. */
  @Query(
      """
      SELECT r FROM ReportingRelation r
      WHERE r.managerEmployeeId = :managerEmployeeId
        AND r.status = 'ACTIVE'
        AND r.effectiveFrom <= :on
        AND (r.effectiveTo IS NULL OR r.effectiveTo > :on)
      ORDER BY r.subordinateEmployeeId ASC
      """)
  List<ReportingRelation> findActiveSubordinates(
      @Param("managerEmployeeId") UUID managerEmployeeId, @Param("on") LocalDate on);
}
