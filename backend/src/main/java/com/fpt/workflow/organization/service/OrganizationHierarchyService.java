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
  private final com.fpt.workflow.organization.repository.ReportingRelationRepository
      reportingRelationRepository;

  public OrganizationHierarchyService(
      EmployeeRepository employeeRepository,
      PositionRepository positionRepository,
      PositionAssignmentRepository assignmentRepository) {
    this(employeeRepository, positionRepository, assignmentRepository, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public OrganizationHierarchyService(
      EmployeeRepository employeeRepository,
      PositionRepository positionRepository,
      PositionAssignmentRepository assignmentRepository,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.organization.repository.ReportingRelationRepository
          reportingRelationRepository) {
    this.employeeRepository = employeeRepository;
    this.positionRepository = positionRepository;
    this.assignmentRepository = assignmentRepository;
    this.reportingRelationRepository = reportingRelationRepository;
  }

  /**
   * Primary organization unit of a user, derived from their active primary position (spec §8.7
   * getPrimaryUnit). Returns empty when the user has no active employee record or assignment.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> getPrimaryUnit(UUID userId, LocalDate effectiveDate) {
    Optional<Employee> employee = employeeRepository.findByUserId(userId);
    if (employee.isEmpty() || !employee.get().isActive()) {
      return Optional.empty();
    }
    return assignmentRepository
        .findActivePrimaryAssignment(employee.get().getId(), effectiveDate)
        .map(PositionAssignment::getPositionId)
        .flatMap(positionRepository::findById)
        .map(Position::getOrgUnitId);
  }

  /**
   * Reports whether managerUserId manages userId through the position hierarchy up to maxDepth
   * levels (depth &lt;= 0 means unrestricted) or an active reporting relation (spec §8.7
   * isManagerOf).
   */
  @Transactional(readOnly = true)
  public boolean isManagerOf(UUID managerUserId, UUID userId, int maxDepth, LocalDate effectiveDate) {
    Optional<Employee> employee = employeeRepository.findByUserId(userId);
    if (employee.isEmpty() || !employee.get().isActive()) {
      return false;
    }
    // Reporting relations first: an active override makes the relation manager real regardless of
    // the position tree (§8.5 primary override if present).
    if (reportingRelationRepository != null) {
      UUID employeeId = employee.get().getId();
      for (com.fpt.workflow.organization.domain.ReportingRelation relation :
          reportingRelationRepository.findActiveOverrides(employeeId, effectiveDate)) {
        Optional<Employee> manager =
            employeeRepository.findById(relation.getManagerEmployeeId());
        if (manager.isPresent()
            && manager.get().isActive()
            && manager.get().getUserId().equals(managerUserId)) {
          return true;
        }
      }
    }
    // Position hierarchy walk: check each ancestor manager level against the claimed manager.
    int levelsChecked = 0;
    UUID current = userId;
    while (maxDepth <= 0 || levelsChecked < maxDepth) {
      UUID managerUserIdAtLevel;
      try {
        managerUserIdAtLevel = resolveDirectManagerUserId(current, effectiveDate);
      } catch (ManagerNotFoundException exhausted) {
        return false;
      }
      if (managerUserIdAtLevel.equals(managerUserId)) {
        return true;
      }
      current = managerUserIdAtLevel;
      levelsChecked++;
    }
    return false;
  }

  /**
   * Subordinate user IDs under a manager within the given depth (1 = direct only) combining the
   * position closure and active reporting relations (spec §8.7 getSubordinates(depth)).
   */
  @Transactional(readOnly = true)
  public List<UUID> getSubordinates(UUID managerUserId, int depth, LocalDate effectiveDate) {
    if (depth < 1) {
      throw new IllegalArgumentException("depth must be >= 1");
    }
    List<UUID> result =
        new java.util.ArrayList<>(
            resolveSubordinateUserIdsAtDepth(managerUserId, depth, effectiveDate));

    if (reportingRelationRepository != null) {
      Optional<Employee> managerEmployee = employeeRepository.findByUserId(managerUserId);
      if (managerEmployee.isPresent()) {
        for (com.fpt.workflow.organization.domain.ReportingRelation relation :
            reportingRelationRepository.findActiveSubordinates(
                managerEmployee.get().getId(), effectiveDate)) {
          employeeRepository
              .findById(relation.getSubordinateEmployeeId())
              .filter(Employee::isActive)
              .map(Employee::getUserId)
              .ifPresent(result::add);
        }
      }
    }
    return List.copyOf(new java.util.LinkedHashSet<>(result));
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
   * Resolves the head of the organization unit of the user's primary position on the effective date.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> resolveHeadOfUnitForUser(
      UUID userId, String unitType, LocalDate effectiveDate) {
    Optional<Employee> empOpt = employeeRepository.findByUserId(userId);
    if (empOpt.isEmpty() || !empOpt.get().isActive()) {
      return Optional.empty();
    }
    Optional<PositionAssignment> primaryOpt =
        assignmentRepository.findActivePrimaryAssignment(empOpt.get().getId(), effectiveDate);
    if (primaryOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<Position> posOpt = positionRepository.findById(primaryOpt.get().getPositionId());
    if (posOpt.isEmpty()) {
      return Optional.empty();
    }
    return resolveDepartmentHeadUserId(posOpt.get().getOrgUnitId(), effectiveDate);
  }

  /**
   * Returns employee user IDs that directly report to the given userId via the position closure
   * (positions whose direct manager position is occupied by the given user).
   */
  @Transactional(readOnly = true)
  public List<UUID> resolveDirectSubordinateUserIds(UUID managerUserId, LocalDate effectiveDate) {
    return getSubordinates(managerUserId, 1, effectiveDate);
  }

  /**
   * Subordinate user IDs under a manager within the given position-closure depth (1 = direct only).
   * Depth N walks descendant positions at closure depth &lt;= N relative to the manager's primary
   * position and collects their active occupants.
   */
  private List<UUID> resolveSubordinateUserIdsAtDepth(
      UUID managerUserId, int depth, LocalDate effectiveDate) {
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

    List<UUID> descendantPositionIds = positionRepository.findAllDescendantIds(primary.getPositionId());

    // Position closure rows: ancestors are ordered by depth ascending and include depth 0 (self).
    // A descendant at closure depth d from the root position appears at index d in the ancestor
    // list of that descendant. We compute each descendant's depth from its own ancestor list.
    List<UUID> result = new ArrayList<>();
    for (UUID posId : descendantPositionIds) {
      if (posId.equals(primary.getPositionId())) continue; // skip self
      List<UUID> ancestors = positionRepository.findAllAncestorIds(posId);
      int indexOfPrimary = ancestors.indexOf(primary.getPositionId());
      if (indexOfPrimary < 0 || indexOfPrimary > depth) continue;
      List<PositionAssignment> occupants =
          assignmentRepository.findActiveAssignmentsForPosition(posId, effectiveDate);
      for (PositionAssignment occ : occupants) {
        employeeRepository
            .findById(occ.getEmployeeId())
            .filter(Employee::isActive)
            .map(Employee::getUserId)
            .ifPresent(result::add);
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
