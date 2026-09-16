package com.fpt.workflow.tenant.service;

import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.tenant.domain.Tenant;
import com.fpt.workflow.tenant.domain.TenantMembership;
import com.fpt.workflow.tenant.repository.TenantMembershipRepository;
import com.fpt.workflow.tenant.repository.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantAccessService {
  private final TenantRepository tenants;
  private final TenantMembershipRepository memberships;
  private final ActorContextProvider actors;

  public TenantAccessService(
      TenantRepository tenants, TenantMembershipRepository memberships, ActorContextProvider actors) {
    this.tenants = tenants;
    this.memberships = memberships;
    this.actors = actors;
  }

  @Transactional(readOnly = true)
  public List<TenantOption> listVisible() {
    ActorContext actor = actors.requireActor();
    if (actor.hasRole(RoleKey.ADMIN)) {
      return tenants.findAllByStatusOrderByNameAsc("ACTIVE").stream().map(TenantOption::of).toList();
    }
    return memberships.findAllByUserIdAndStatusOrderByTenantId(actor.actorId(), "ACTIVE").stream()
        .map(TenantMembership::getTenantId)
        .map(id -> tenants.findById(id).orElse(null))
        .filter(t -> t != null && "ACTIVE".equals(t.getStatus()))
        .map(TenantOption::of)
        .toList();
  }

  @Transactional(readOnly = true)
  public Tenant requireReadable(UUID tenantId) {
    Tenant tenant = requireActive(tenantId);
    ActorContext actor = actors.requireActor();
    if (actor.hasRole(RoleKey.ADMIN) || isMember(tenantId, actor.actorId())) return tenant;
    throw new AccessDeniedException("TENANT_ACCESS_DENIED");
  }

  @Transactional(readOnly = true)
  public Tenant requireManageable(UUID tenantId) {
    Tenant tenant = requireActive(tenantId);
    ActorContext actor = actors.requireActor();
    if (actor.hasRole(RoleKey.ADMIN)) return tenant;
    boolean workflowManager = actor.hasRole(RoleKey.WORKFLOW_OWNER) || actor.hasRole(RoleKey.WORKFLOW_EDITOR);
    boolean tenantAdmin = memberships.findByTenantIdAndUserId(tenantId, actor.actorId()).map(TenantMembership::isTenantAdmin).orElse(false);
    if (workflowManager && tenantAdmin) return tenant;
    throw new AccessDeniedException("TENANT_MANAGEMENT_DENIED");
  }

  @Transactional(readOnly = true)
  public void requireUsable(UUID tenantId) {
    requireReadable(tenantId);
  }

  private boolean isMember(UUID tenantId, UUID userId) {
    return memberships.findByTenantIdAndUserId(tenantId, userId).map(TenantMembership::isActive).orElse(false);
  }

  private Tenant requireActive(UUID tenantId) {
    return tenants.findById(tenantId)
        .filter(t -> "ACTIVE".equals(t.getStatus()))
        .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("TENANT_NOT_FOUND_OR_INACTIVE"));
  }

  public record TenantOption(UUID id, String key, String name) {
    static TenantOption of(Tenant tenant) { return new TenantOption(tenant.getId(), tenant.getKey(), tenant.getName()); }
  }
}
