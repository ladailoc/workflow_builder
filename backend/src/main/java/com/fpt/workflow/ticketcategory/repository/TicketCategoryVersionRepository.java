package com.fpt.workflow.ticketcategory.repository;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryVersion;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketCategoryVersionRepository extends JpaRepository<TicketCategoryVersion,UUID>{List<TicketCategoryVersion> findAllByTicketCategoryIdOrderByVersionNoDesc(UUID categoryId);List<TicketCategoryVersion> findAllByWorkflowVersionId(UUID workflowVersionId);long countByTicketCategoryId(UUID categoryId);}
