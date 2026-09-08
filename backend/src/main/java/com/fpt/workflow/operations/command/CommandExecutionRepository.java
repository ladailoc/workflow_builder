package com.fpt.workflow.operations.command;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandExecutionRepository extends JpaRepository<CommandExecution, UUID> {

  Optional<CommandExecution> findByScopeTypeAndScopeIdAndCommandId(
      String scopeType, UUID scopeId, UUID commandId);
}
