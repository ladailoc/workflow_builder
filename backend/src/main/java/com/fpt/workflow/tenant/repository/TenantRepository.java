package com.fpt.workflow.tenant.repository;

import com.fpt.workflow.tenant.domain.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
  Optional<Tenant> findByKey(String key);
  List<Tenant> findAllByStatusOrderByNameAsc(String status);
}
