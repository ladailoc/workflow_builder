package com.fpt.workflow.runtime.routing.repository;

import com.fpt.workflow.runtime.routing.domain.ActivationToken;
import com.fpt.workflow.runtime.routing.domain.ActivationTokenStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivationTokenRepository extends JpaRepository<ActivationToken, UUID> {
  Optional<ActivationToken> findByActivationKey(String activationKey);

  List<ActivationToken> findAllByRoutingDecisionId(UUID routingDecisionId);

  List<ActivationToken> findAllByEventIdAndStatus(UUID eventId, ActivationTokenStatus status);

  List<ActivationToken> findAllBySourceNodeExecutionId(UUID sourceNodeExecutionId);
}
