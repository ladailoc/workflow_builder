package com.fpt.workflow.task.aggregation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAggregationVoteRepository extends JpaRepository<TaskAggregationVote, UUID> {}
