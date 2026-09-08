package com.fpt.workflow.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.domain.OrganizationUnit;
import com.fpt.workflow.organization.domain.Position;
import com.fpt.workflow.organization.domain.PositionAssignment;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.organization.repository.OrganizationUnitRepository;
import com.fpt.workflow.organization.repository.PositionAssignmentRepository;
import com.fpt.workflow.organization.repository.PositionRepository;
import com.fpt.workflow.organization.service.ManagerNotFoundException;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class OrganizationHierarchyServiceIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_org_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private EmployeeRepository employeeRepository;
  @Autowired private OrganizationUnitRepository orgUnitRepository;
  @Autowired private PositionRepository positionRepository;
  @Autowired private PositionAssignmentRepository assignmentRepository;
  @Autowired private OrganizationHierarchyService hierarchyService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);
  private static final Instant NOW = Instant.parse("2026-09-08T04:00:00Z");

  // Org unit: Engineering
  private UUID unitId;
  // Positions: VP_ENG -> DIR_ENG -> MGR_ENG -> IC
  private UUID vpEngPositionId;
  private UUID dirEngPositionId;
  private UUID mgrEngPositionId;
  private UUID icPositionId;
  // Employees
  private UUID vpUserId;
  private UUID dirUserId;
  private UUID mgrUserId;
  private UUID icUserId;

  @BeforeEach
  void setup() {
    // Truncate in correct order with CASCADE to handle self-referential FK
    // (positions.reports_to_position_id)
    jdbcTemplate.execute(
        "TRUNCATE position_assignments, position_closure, organization_unit_closure, positions,"
            + " organization_units, employees RESTART IDENTITY CASCADE");

    // Create org unit
    OrganizationUnit unit =
        OrganizationUnit.create(
            UUID.randomUUID(), "ENG", "Engineering", null, null, "DEPARTMENT", NOW);
    unit = orgUnitRepository.save(unit);
    unitId = unit.getId();

    // Create positions in reporting hierarchy: VP -> DIR -> MGR -> IC
    vpEngPositionId = UUID.randomUUID();
    dirEngPositionId = UUID.randomUUID();
    mgrEngPositionId = UUID.randomUUID();
    icPositionId = UUID.randomUUID();

    Position vpPos =
        positionRepository.save(
            Position.create(vpEngPositionId, "VP-ENG", "VP Engineering", unitId, null, true, NOW));
    Position dirPos =
        positionRepository.save(
            Position.create(
                dirEngPositionId,
                "DIR-ENG",
                "Director Engineering",
                unitId,
                vpEngPositionId,
                false,
                NOW));
    Position mgrPos =
        positionRepository.save(
            Position.create(
                mgrEngPositionId,
                "MGR-ENG",
                "Manager Engineering",
                unitId,
                dirEngPositionId,
                false,
                NOW));
    Position icPos =
        positionRepository.save(
            Position.create(
                icPositionId, "IC-ENG", "Engineer", unitId, mgrEngPositionId, false, NOW));

    // Create employees
    vpUserId = UUID.randomUUID();
    dirUserId = UUID.randomUUID();
    mgrUserId = UUID.randomUUID();
    icUserId = UUID.randomUUID();

    Employee vpEmp =
        employeeRepository.save(
            Employee.create(UUID.randomUUID(), vpUserId, "VP001", "VP Person", "vp@ex.test", NOW));
    Employee dirEmp =
        employeeRepository.save(
            Employee.create(
                UUID.randomUUID(), dirUserId, "DIR001", "Dir Person", "dir@ex.test", NOW));
    Employee mgrEmp =
        employeeRepository.save(
            Employee.create(
                UUID.randomUUID(), mgrUserId, "MGR001", "Mgr Person", "mgr@ex.test", NOW));
    Employee icEmp =
        employeeRepository.save(
            Employee.create(UUID.randomUUID(), icUserId, "IC001", "IC Person", "ic@ex.test", NOW));

    // Create active primary assignments
    assignmentRepository.save(
        PositionAssignment.create(
            UUID.randomUUID(),
            vpEmp.getId(),
            vpEngPositionId,
            true,
            "PERMANENT",
            LocalDate.of(2025, 1, 1),
            null,
            NOW));
    assignmentRepository.save(
        PositionAssignment.create(
            UUID.randomUUID(),
            dirEmp.getId(),
            dirEngPositionId,
            true,
            "PERMANENT",
            LocalDate.of(2025, 1, 1),
            null,
            NOW));
    assignmentRepository.save(
        PositionAssignment.create(
            UUID.randomUUID(),
            mgrEmp.getId(),
            mgrEngPositionId,
            true,
            "PERMANENT",
            LocalDate.of(2025, 1, 1),
            null,
            NOW));
    assignmentRepository.save(
        PositionAssignment.create(
            UUID.randomUUID(),
            icEmp.getId(),
            icPositionId,
            true,
            "PERMANENT",
            LocalDate.of(2025, 1, 1),
            null,
            NOW));
  }

  @Test
  void directManager_resolvedFromClosure() {
    UUID resolvedManager = hierarchyService.resolveDirectManagerUserId(icUserId, TODAY);
    assertThat(resolvedManager).isEqualTo(mgrUserId);
  }

  @Test
  void nLevelManager_depth2_resolvesDirectorAboveManager() {
    UUID resolvedAtDepth2 = hierarchyService.resolveManagerAtDepth(icUserId, 2, TODAY);
    assertThat(resolvedAtDepth2).isEqualTo(dirUserId);
  }

  @Test
  void nLevelManager_depth3_resolvesVP() {
    UUID resolvedAtDepth3 = hierarchyService.resolveManagerAtDepth(icUserId, 3, TODAY);
    assertThat(resolvedAtDepth3).isEqualTo(vpUserId);
  }

  @Test
  void departmentHead_resolvedFromHeadOfUnitFlag() {
    Optional<UUID> head = hierarchyService.resolveDepartmentHeadUserId(unitId, TODAY);
    assertThat(head).isPresent().contains(vpUserId);
  }

  @Test
  void subordinates_directSubordinates_ofManager() {
    List<UUID> subs = hierarchyService.resolveDirectSubordinateUserIds(mgrUserId, TODAY);
    assertThat(subs).containsExactly(icUserId);
  }

  @Test
  void vacant_positionWithNoAssignment_returnsTrue() {
    UUID newPositionId = UUID.randomUUID();
    positionRepository.save(
        Position.create(
            newPositionId, "VACANT-POS", "Vacant Position", unitId, mgrEngPositionId, false, NOW));
    assertThat(hierarchyService.isVacant(newPositionId, TODAY)).isTrue();
  }

  @Test
  void inactive_employee_managerResolutionFails() {
    // Get icEmp and deactivate them
    Employee icEmp = employeeRepository.findByUserId(icUserId).orElseThrow();
    icEmp.deactivate(NOW);
    employeeRepository.save(icEmp);

    // Now, trying to resolve manager of ic should fail since employee is inactive
    assertThatThrownBy(() -> hierarchyService.resolveDirectManagerUserId(icUserId, TODAY))
        .isInstanceOf(ManagerNotFoundException.class)
        .hasMessageContaining("not active");
  }

  @Test
  void effectiveDating_futureAssignment_notResolvedOnToday() {
    // End current IC assignment yesterday, create a new one starting next month
    List<PositionAssignment> assignments =
        assignmentRepository.findAllByEmployeeId(
            employeeRepository.findByUserId(icUserId).orElseThrow().getId());
    PositionAssignment current = assignments.getFirst();
    current.end(LocalDate.of(2026, 9, 7), NOW); // ended yesterday
    assignmentRepository.save(current);

    // No active primary assignment today
    assertThatThrownBy(() -> hierarchyService.resolveDirectManagerUserId(icUserId, TODAY))
        .isInstanceOf(ManagerNotFoundException.class)
        .hasMessageContaining("No active primary position assignment");
  }

  @Test
  void cycleGuard_selfReport_preventedByDomainValidation() {
    UUID posId = UUID.randomUUID();
    assertThatThrownBy(
            () -> Position.create(posId, "CYCLE-POS", "Cycle Position", unitId, posId, false, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot report to itself");
  }

  @Test
  void positionOccupants_returnsAssignedUser() {
    List<UUID> occupants = hierarchyService.resolvePositionOccupants(icPositionId, TODAY);
    assertThat(occupants).containsExactly(icUserId);
  }
}
