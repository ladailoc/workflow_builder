package com.fpt.workflow.operations.outbox;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
  Optional<OutboxEvent> findByDedupKey(String dedupKey);

  List<OutboxEvent> findAllByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
