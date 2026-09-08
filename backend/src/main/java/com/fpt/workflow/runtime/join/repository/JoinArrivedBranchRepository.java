package com.fpt.workflow.runtime.join.repository;

import com.fpt.workflow.runtime.join.domain.JoinArrivedBranch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JoinArrivedBranchRepository extends JpaRepository<JoinArrivedBranch, UUID> {

  Optional<JoinArrivedBranch> findByJoinStateIdAndInboundExecutionId(
      UUID joinStateId, UUID inboundExecutionId);

  List<JoinArrivedBranch> findAllByJoinStateIdOrderByArrivedAtAsc(UUID joinStateId);
}
