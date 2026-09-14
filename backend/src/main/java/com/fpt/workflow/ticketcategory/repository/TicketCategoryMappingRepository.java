package com.fpt.workflow.ticketcategory.repository;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryMapping;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketCategoryMappingRepository extends JpaRepository<TicketCategoryMapping,UUID>{List<TicketCategoryMapping> findAllByCategoryVersionIdOrderByOrdinalAsc(UUID versionId);void deleteAllByCategoryVersionId(UUID versionId);}
