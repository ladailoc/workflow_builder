package com.fpt.workflow.resolver.repository;

import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantSnapshotRepository extends JpaRepository<ParticipantSnapshot, UUID> {

  List<ParticipantSnapshot> findAllByEventIdOrderByResolvedAtAsc(UUID eventId);

  List<ParticipantSnapshot> findAllByNodeExecutionIdOrderByResolvedAtAsc(UUID nodeExecutionId);
}
