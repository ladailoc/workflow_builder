package com.fpt.workflow.ticketcategory.repository;
import com.fpt.workflow.ticketcategory.domain.TicketCategory;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketCategoryRepository extends JpaRepository<TicketCategory,UUID>{Optional<TicketCategory> findByKey(String key);List<TicketCategory> findAllByLifecycleOrderByNameAsc(String lifecycle);}
