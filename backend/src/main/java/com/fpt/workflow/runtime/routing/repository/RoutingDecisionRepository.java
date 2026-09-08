package com.fpt.workflow.runtime.routing.repository;

import com.fpt.workflow.runtime.routing.domain.RoutingDecision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingDecisionRepository extends JpaRepository<RoutingDecision, UUID> {
  Optional<RoutingDecision> findBySourceNodeExecutionId(UUID sourceNodeExecutionId);
}
