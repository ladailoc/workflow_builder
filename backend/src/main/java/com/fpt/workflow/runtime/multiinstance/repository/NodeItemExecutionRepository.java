package com.fpt.workflow.runtime.multiinstance.repository;

import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeItemExecutionRepository extends JpaRepository<NodeItemExecution, UUID> {

  List<NodeItemExecution> findAllByMultiInstanceStateIdOrderByItemIndexAsc(
      UUID multiInstanceStateId);

  List<NodeItemExecution> findAllByParentNodeExecutionIdOrderByItemIndexAsc(
      UUID parentNodeExecutionId);

  Optional<NodeItemExecution> findByParentNodeExecutionIdAndItemIndex(
      UUID parentNodeExecutionId, int itemIndex);
}
