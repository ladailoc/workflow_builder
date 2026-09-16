package com.fpt.workflow.organization.service;

import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.domain.OrganizationUnit;
import com.fpt.workflow.organization.domain.Position;
import com.fpt.workflow.organization.domain.PositionAssignment;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.organization.repository.OrganizationUnitRepository;
import com.fpt.workflow.organization.repository.PositionAssignmentRepository;
import com.fpt.workflow.organization.repository.PositionRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model used by the administrator directory. Runtime resolution remains in the hierarchy service. */
@Service
public class OrganizationDirectoryService {
  private final EmployeeRepository employees;
  private final OrganizationUnitRepository units;
  private final PositionRepository positions;
  private final PositionAssignmentRepository assignments;

  public OrganizationDirectoryService(
      EmployeeRepository employees,
      OrganizationUnitRepository units,
      PositionRepository positions,
      PositionAssignmentRepository assignments) {
    this.employees = employees;
    this.units = units;
    this.positions = positions;
    this.assignments = assignments;
  }

  @Transactional(readOnly = true)
  public DirectorySnapshot snapshot() {
    LocalDate today = LocalDate.now();
    List<UnitView> unitViews = units.findAll().stream()
        .sorted(Comparator.comparing(OrganizationUnit::getName, String.CASE_INSENSITIVE_ORDER))
        .map(u -> new UnitView(u.getId(), u.getUnitCode(), u.getName(), u.getUnitType(),
            u.getParentUnitId(), u.getManagerPositionId(), u.getStatus()))
        .toList();
    List<Position> positionEntities = positions.findAll();
    var positionById = positionEntities.stream().collect(java.util.stream.Collectors.toMap(Position::getId, p -> p));
    var employeeEntities = employees.findAll();
    var employeeById = employeeEntities.stream().collect(java.util.stream.Collectors.toMap(Employee::getId, e -> e));
    List<PositionView> positionViews = positionEntities.stream()
        .sorted(Comparator.comparing(Position::getTitle, String.CASE_INSENSITIVE_ORDER))
        .map(p -> new PositionView(p.getId(), p.getPositionCode(), p.getTitle(), p.getOrgUnitId(),
            p.getReportsToPositionId(), p.isHeadOfUnit(), p.getLevel(), p.getStatus(),
            assignments.findActiveAssignmentsForPosition(p.getId(), today).stream()
                .map(PositionAssignment::getEmployeeId).toList()))
        .toList();
    List<EmployeeView> employeeViews = employeeEntities.stream()
        .sorted(Comparator.comparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER))
        .map(e -> employeeView(e, today, positionById, employeeById))
        .toList();
    return new DirectorySnapshot(unitViews, positionViews, employeeViews,
        unitViews.size(), positionViews.size(), employeeViews.size());
  }

  public record DirectorySnapshot(
      List<UnitView> units,
      List<PositionView> positions,
      List<EmployeeView> employees,
      int unitCount,
      int positionCount,
      int employeeCount) {}

  public record UnitView(UUID id, String unitCode, String name, String unitType,
                         UUID parentUnitId, UUID managerPositionId, String status) {}

  public record PositionView(UUID id, String positionCode, String title, UUID orgUnitId,
                             UUID reportsToPositionId, boolean headOfUnit, int level,
                             String status, List<UUID> activeEmployeeIds) {}

  private EmployeeView employeeView(Employee employee, LocalDate today,
      java.util.Map<UUID, Position> positionById, java.util.Map<UUID, Employee> employeeById) {
    var assignment = assignments.findActivePrimaryAssignment(employee.getId(), today).orElse(null);
    var position = assignment == null ? null : positionById.get(assignment.getPositionId());
    UUID orgUnitId = position == null ? null : position.getOrgUnitId();
    UUID managerUserId = null;
    if (position != null && position.getReportsToPositionId() != null) {
      var managerAssignment = assignments.findActiveAssignmentsForPosition(position.getReportsToPositionId(), today)
          .stream().findFirst().orElse(null);
      if (managerAssignment != null) {
        var manager = employeeById.get(managerAssignment.getEmployeeId());
        if (manager != null && manager.isActive()) managerUserId = manager.getUserId();
      }
    }
    boolean active = employee.isActive();
    return new EmployeeView(employee.getId(), employee.getUserId(), employee.getEmployeeCode(),
        employee.getEmployeeCode(), employee.getFullName(), employee.getFullName(),
        employee.getEmail(), employee.getStatus(), active,
        assignment == null ? null : assignment.getPositionId(), orgUnitId, orgUnitId,
        managerUserId, managerUserId, List.of(), List.of());
  }

  /**
   * Directory identity uses the names from the workflow contract and keeps the richer internal
   * names as aliases for existing UI consumers. Roles/groups are empty until an external directory
   * sync supplies those memberships; participant resolvers still enforce their own source policy.
   */
  public record EmployeeView(UUID id, UUID userId, String employeeCode, String externalId,
                             String fullName, String displayName, String email, String status,
                             boolean active, UUID primaryPositionId, UUID orgUnitId,
                             UUID departmentId, UUID managerUserId, UUID managerId,
                             List<String> roles, List<String> groups) {}
}
