package com.fpt.workflow.rework.repository;

import com.fpt.workflow.rework.domain.RevisionRequestedValue;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionRequestedValueRepository
    extends JpaRepository<RevisionRequestedValue, UUID> {}
