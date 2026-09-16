package com.fpt.workflow.ticketcategory.dto;

import com.fpt.workflow.ticketcategory.service.TicketCategoryBindingService;

public final class TicketCategoryBindingDtos {
  private TicketCategoryBindingDtos() {}

  public record ScopeBindingView(
      String state,
      java.util.UUID tenantId,
      String tenantKey,
      String tenantName,
      java.util.UUID categoryVersionId,
      java.util.UUID formVersionId,
      String formName,
      int formVersionNo,
      java.util.UUID workflowVersionId,
      String workflowName,
      int workflowVersionNo,
      long lockVersion,
      boolean canEdit) {
    public static ScopeBindingView from(TicketCategoryBindingService.ScopeBindingView source) {
      return new ScopeBindingView(source.state(), source.tenantId(), source.tenantKey(), source.tenantName(), source.categoryVersionId(), source.formVersionId(), source.formName(), source.formVersionNo(), source.workflowVersionId(), source.workflowName(), source.workflowVersionNo(), source.lockVersion(), source.canEdit());
    }
  }
}
