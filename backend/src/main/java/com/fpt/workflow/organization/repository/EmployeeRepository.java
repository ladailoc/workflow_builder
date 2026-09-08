package com.fpt.workflow.organization.repository;

import com.fpt.workflow.organization.domain.Employee;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
  Optional<Employee> findByUserId(UUID userId);

  Optional<Employee> findByEmployeeCode(String employeeCode);
}
