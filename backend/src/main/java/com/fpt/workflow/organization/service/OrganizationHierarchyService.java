package com.fpt.workflow.organization.service;

import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.domain.Position;
import com.fpt.workflow.organization.domain.PositionAssignment;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.organization.repository.PositionAssignmentRepository;
import com.fpt.workflow.organization.repository.PositionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides canonical organization hierarchy lookups: manager-of chain, department head, direct and
 * N-level subordinates, vacant positions, inactive employees. All queries use closure tables for
 * O(1) ancestor/descendant lookups.
 */
@Service
public class OrganizationHierarchyService {

  private final EmployeeRepository employeeRepository;
  private final PositionRepository positionRepository;
  private final PositionAssignmentRepository assignmentRepository;

  public OrganizationHierarchyService(
      EmployeeRepository employeeRepository,
      PositionRepository positionRepository,
      PositionAssignmentRepository assignmentRepository) {
    this.employeeRepository = employeeRepository;
    this.positionRepository = positionRepository;
    this.assignmentRepository = assignmentRepository;
  }

  /**
   * Returns the user ID of the direct manager of the given userId on the given effective date. Uses
   * the position closure table (depth=1 ancestor) and then resolves the occupant of that position.
   *
   * @throws ManagerNotFoundException if the employee has no primary assignment or no one occupies
   *     the manager position.
   */
  @Transactional(readOnly = true, noRollbackFor = ManagerNotFoundException.class)
  public UUID resolveDirectManagerUserId(UUID userId, LocalDate effectiveDate) {
    Employee employee =
        employeeRepository
            .findByUserId(userId)
            .orElseThrow(
                () -> new ManagerNotFoundException("Employee not found for user: " + userId));
    if (!employee.isActive()) {
      throw new ManagerNotFoundException("Employee is not active: " + userId);
    }
    PositionAssignment primary =
        assignmentRepository
            .findActivePrimaryAssignment(employee.getId(), effectiveDate)
            .orElseThrow(
                () ->
                    new ManagerNotFoundException(
                        "No active primary position assignment for employee: "
                            + employee.getId()
                            + " on "
                            + effectiveDate));

    UUID managerPositionId =
        positionRepository
            .findDirectManagerPositionId(primary.getPositionId())
            .orElseThrow(
                () ->
                    new ManagerNotFoundException(
                        "No manager position found for position: " + primary.getPositionId()));

    List<PositionAssignment> managerAssignments =
        assignmentRepository.findActiveAssignmentsForPosition(managerPositionId, effectiveDate);
    PositionAssignment managerAssignment =
        managerAssignments.stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ManagerNotFoundException(
                        "Manager position "
                            + managerPositionId
                            + " is vacant on "
                            + effectiveDate));

    Employee manager =
        employeeRepository
            .findById(managerAssignment.getEmployeeId())
            .orElseThrow(() -> new ManagerNotFoundException("Manager employee record not found"));
    return manager.getUserId();
  }

  /**
   * Returns the N-level manager user ID. Depth 1 = direct manager, depth 2 = manager's manager,
   * etc.
   */
  @Transactional(readOnly = true, noRollbackFor = ManagerNotFoundException.class)
  public UUID resolveManagerAtDepth(UUID userId, int depth, LocalDate effectiveDate) {
    if (depth < 1) throw new IllegalArgumentException("depth must be >= 1");
    UUID current = userId;
    for (int i = 0; i < depth; i++) {
      current = resolveDirectManagerUserId(current, effectiveDate);
    }
    return current;
  }

  /**
   * Returns the user ID of the department/unit head on the given effective date. Uses the
   * is_head_of_unit flag on positions within the org unit.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> resolveDepartmentHeadUserId(UUID orgUnitId, LocalDate effectiveDate) {
    List<Position> heads = positionRepository.findActiveHeadsOfUnit(orgUnitId);
    for (Position head : heads) {
      List<PositionAssignment> assignments =
          assignmentRepository.findActiveAssignmentsForPosition(head.getId(), effectiveDate);
      if (!assignments.isEmpty()) {
        return employeeRepository
            .findById(assignments.getFirst().getEmployeeId())
            .map(Employee::getUserId);
      }
    }
    return Optional.empty();
  }

  /**
   * Returns employee user IDs that directly report to the given userId via the position closure
   * (positions whose direct manager position is occupied by the given user).
   */
  @Transactional(readOnly = true)
  public List<UUID> resolveDirectSubordinateUserIds(UUID managerUserId, LocalDate effectiveDate) {
    Employee manager =
        employeeRepository
            .findByUserId(managerUserId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException("Employee not found for user: " + managerUserId));
    PositionAssignment primary =
        assignmentRepository
            .findActivePrimaryAssignment(manager.getId(), effectiveDate)
            .orElse(null);
    if (primary == null) return List.of();

    List<UUID> subordinatePositionIds =
        positionRepository.findAllDescendantIds(primary.getPositionId());

    List<UUID> result = new ArrayList<>();
    for (UUID posId : subordinatePositionIds) {
      if (posId.equals(primary.getPositionId())) continue; // skip self
      UUID depth = null; // only direct children
      // depth=1 in closure means direct subordinate
      List<UUID> ancestors = positionRepository.findAllAncestorIds(posId);
      if (ancestors.size() >= 2 && ancestors.get(1).equals(primary.getPositionId())) {
        // depth 1: direct subordinate
        List<PositionAssignment> occupants =
            assignmentRepository.findActiveAssignmentsForPosition(posId, effectiveDate);
        for (PositionAssignment occ : occupants) {
          employeeRepository
              .findById(occ.getEmployeeId())
              .map(Employee::getUserId)
              .ifPresent(result::add);
        }
      }
    }
    return List.copyOf(result);
  }

  /**
   * Returns all active employees (user IDs) holding the given position on the reference date.
   * Returns empty list if the position is vacant.
   */
  @Transactional(readOnly = true)
  public List<UUID> resolvePositionOccupants(UUID positionId, LocalDate effectiveDate) {
    return assignmentRepository.findActiveAssignmentsForPosition(positionId, effectiveDate).stream()
        .map(PositionAssignment::getEmployeeId)
        .map(employeeRepository::findById)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .filter(Employee::isActive)
        .map(Employee::getUserId)
        .toList();
  }

  /** Returns true if the given position is vacant (no active assignment) on the reference date. */
  @Transactional(readOnly = true)
  public boolean isVacant(UUID positionId, LocalDate effectiveDate) {
    return assignmentRepository
        .findActiveAssignmentsForPosition(positionId, effectiveDate)
        .isEmpty();
  }
}
