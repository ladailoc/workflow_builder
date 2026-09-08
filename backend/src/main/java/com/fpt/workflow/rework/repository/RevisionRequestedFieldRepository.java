package com.fpt.workflow.rework.repository;

import com.fpt.workflow.rework.domain.RevisionRequestedField;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionRequestedFieldRepository
    extends JpaRepository<RevisionRequestedField, UUID> {
  List<RevisionRequestedField> findAllByRevisionRequestIdOrderByOrdinalAsc(UUID revisionRequestId);
}
