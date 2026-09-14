package com.fpt.workflow.runtime.context;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EventWorkflowInputSnapshotRepository extends JpaRepository<EventWorkflowInputSnapshot,UUID>{}
