package com.fpt.workflow.ticketcategory.repository;

import com.fpt.workflow.ticketcategory.domain.TicketCategoryWorkflowBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCategoryWorkflowBindingRepository
    extends JpaRepository<TicketCategoryWorkflowBinding, UUID> {
  Optional<TicketCategoryWorkflowBinding> findByTicketCategoryIdAndTenantId(
      UUID ticketCategoryId, UUID tenantId);

  List<TicketCategoryWorkflowBinding> findAllByTicketCategoryIdOrderByTenantId(UUID ticketCategoryId);

  void deleteByTicketCategoryIdAndTenantId(UUID ticketCategoryId, UUID tenantId);
}
