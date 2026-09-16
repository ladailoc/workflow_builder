package com.fpt.workflow.ticketcategory.service;

import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowInputDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.FormDefinition;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.repository.FormDefinitionRepository;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.ticketcategory.domain.TicketCategory;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryVersion;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryWorkflowBinding;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryWorkflowBindingRepository;
import com.fpt.workflow.tenant.service.TenantAccessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketCategoryBindingService {
  private final TicketCategoryRepository categories;
  private final TicketCategoryVersionRepository categoryVersions;
  private final TicketCategoryWorkflowBindingRepository bindings;
  private final FormDefinitionRepository formDefinitions;
  private final FormVersionRepository formVersions;
  private final WorkflowVersionRepository workflowVersions;
  private final WorkflowDefinitionRepository workflowDefinitions;
  private final WorkflowInputDefinitionRepository workflowInputs;
  private final CategoryValidationService validation;
  private final TenantAccessService tenantAccess;
  private final ActorContextProvider actors;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public TicketCategoryBindingService(
      TicketCategoryRepository categories,
      TicketCategoryVersionRepository categoryVersions,
      TicketCategoryWorkflowBindingRepository bindings,
      FormDefinitionRepository formDefinitions,
      FormVersionRepository formVersions,
      WorkflowVersionRepository workflowVersions,
      WorkflowDefinitionRepository workflowDefinitions,
      WorkflowInputDefinitionRepository workflowInputs,
      CategoryValidationService validation,
      TenantAccessService tenantAccess,
      ActorContextProvider actors,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.categories = categories;
    this.categoryVersions = categoryVersions;
    this.bindings = bindings;
    this.formDefinitions = formDefinitions;
    this.formVersions = formVersions;
    this.workflowVersions = workflowVersions;
    this.workflowDefinitions = workflowDefinitions;
    this.workflowInputs = workflowInputs;
    this.validation = validation;
    this.tenantAccess = tenantAccess;
    this.actors = actors;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public BindingOverview describe(UUID categoryId) {
    TicketCategory category = requireCategory(categoryId);
    TicketCategoryVersion categoryVersion = requirePublishedVersion(category);
    FormVersion formVersion = requirePublishedForm(categoryVersion);
    FormDefinition form = formDefinitions.findById(formVersion.getFormId()).orElse(null);
    if (form == null) throw new CommandConflictException("CATEGORY_FORM_NOT_FOUND", "Shared Form was not found");

    TicketCategoryWorkflowBinding defaultBinding = bindings.findByTicketCategoryIdAndTenantId(categoryId, null).orElse(null);
    UUID defaultWorkflowId = defaultBinding == null ? categoryVersion.getWorkflowVersionId() : defaultBinding.getWorkflowVersionId();
    List<ScopeBindingView> scopes = new ArrayList<>();
    scopes.add(scope("DEFAULT", null, null, null, defaultWorkflowId, categoryVersion, formVersion, form, defaultBinding, true));
    for (TenantAccessService.TenantOption tenant : tenantAccess.listVisible()) {
      TicketCategoryWorkflowBinding override = bindings.findByTicketCategoryIdAndTenantId(categoryId, tenant.id()).orElse(null);
      scopes.add(scope(override == null ? "INHERITED" : "OVERRIDE", tenant.id(), tenant.key(), tenant.name(),
          override == null ? defaultWorkflowId : override.getWorkflowVersionId(), categoryVersion, formVersion, form, override, canManage(tenant.id())));
    }
    return new BindingOverview(category.getId(), category.getKey(), category.getName(),
        new FormSummary(form.getId(), form.getKey(), form.getName(), formVersion.getId(), formVersion.getVersionNo()), List.copyOf(scopes));
  }

  @Transactional
  public ScopeBindingView upsertOverride(UUID categoryId, OverrideCommand command) {
    if (command.tenantId() == null) throw new IllegalArgumentException("TENANT_ID_REQUIRED_FOR_OVERRIDE");
    tenantAccess.requireManageable(command.tenantId());
    TicketCategory category = requireCategory(categoryId);
    TicketCategoryVersion categoryVersion = requirePublishedVersion(category);
    requireCompatibleWorkflow(categoryVersion, command.workflowVersionId());
    WorkflowVersion workflow = requirePublishedWorkflow(command.workflowVersionId());
    FormVersion formVersion = requirePublishedForm(categoryVersion);
    FormDefinition form = formDefinitions.findById(formVersion.getFormId()).orElseThrow();
    Instant now = clock.now();
    TicketCategoryWorkflowBinding binding = bindings.findByTicketCategoryIdAndTenantId(categoryId, command.tenantId()).orElse(null);
    if (binding == null) {
      binding = TicketCategoryWorkflowBinding.create(uuids.generate(), categoryId, command.tenantId(), categoryVersion.getId(), workflow.getId(), actors.requireActor().actorId(), now);
    } else {
      if (command.expectedLockVersion() == null) throw new IllegalStateException("STALE_TENANT_BINDING");
      binding.update(command.expectedLockVersion(), categoryVersion.getId(), workflow.getId(), now);
    }
    binding = bindings.saveAndFlush(binding);
    TenantAccessService.TenantOption tenant = tenantAccess.listVisible().stream().filter(t -> t.id().equals(command.tenantId())).findFirst().orElseGet(() -> new TenantAccessService.TenantOption(command.tenantId(), "", "Tenant"));
    return scope("OVERRIDE", tenant.id(), tenant.key(), tenant.name(), binding.getWorkflowVersionId(), categoryVersion, formVersion, form, binding, true);
  }

  @Transactional
  public void deleteOverride(UUID categoryId, UUID tenantId) {
    if (tenantId == null) throw new IllegalArgumentException("TENANT_ID_REQUIRED_FOR_OVERRIDE");
    tenantAccess.requireManageable(tenantId);
    requireCategory(categoryId);
    bindings.deleteByTicketCategoryIdAndTenantId(categoryId, tenantId);
  }

  /** Keeps the default binding and existing tenant overrides aligned to a newly published category version. */
  @Transactional
  public void syncAfterCategoryPublish(UUID categoryId, TicketCategoryVersion categoryVersion) {
    Instant now = clock.now();
    TicketCategoryWorkflowBinding defaultBinding = bindings.findByTicketCategoryIdAndTenantId(categoryId, null).orElse(null);
    if (defaultBinding == null) {
      defaultBinding = TicketCategoryWorkflowBinding.create(uuids.generate(), categoryId, null, categoryVersion.getId(), categoryVersion.getWorkflowVersionId(), actors.requireActor().actorId(), now);
    } else {
      defaultBinding.syncToPublished(categoryVersion.getId(), categoryVersion.getWorkflowVersionId(), now);
    }
    bindings.save(defaultBinding);
    for (TicketCategoryWorkflowBinding binding : bindings.findAllByTicketCategoryIdOrderByTenantId(categoryId)) {
      if (binding.getTenantId() != null && !binding.getCategoryVersionId().equals(categoryVersion.getId())) {
        binding.rebaseCategoryVersion(categoryVersion.getId(), now);
      }
    }
  }

  public boolean canManage(UUID tenantId) {
    try { tenantAccess.requireManageable(tenantId); return true; }
    catch (AccessDeniedException | IllegalArgumentException ex) { return false; }
  }

  private ScopeBindingView scope(String state, UUID tenantId, String tenantKey, String tenantName, UUID workflowId,
      TicketCategoryVersion categoryVersion, FormVersion formVersion, FormDefinition form,
      TicketCategoryWorkflowBinding binding, boolean canEdit) {
    WorkflowVersion workflow = requirePublishedWorkflow(workflowId);
    WorkflowDefinition definition = workflowDefinitions.findById(workflow.getDefinitionId()).orElse(null);
    String workflowName = definition == null ? workflow.getDefinitionId().toString() : definition.getName();
    return new ScopeBindingView(state, tenantId, tenantKey, tenantName, categoryVersion.getId(), formVersion.getId(), form.getName(), formVersion.getVersionNo(), workflow.getId(), workflowName, workflow.getVersionNo(), binding == null ? 0 : binding.getLockVersion(), canEdit);
  }

  private void requireCompatibleWorkflow(TicketCategoryVersion version, UUID workflowVersionId) {
    List<CategoryIssue> issues = validation.validateWorkflowCompatibility(version, workflowVersionId);
    if (issues.stream().anyMatch(i -> "ERROR".equals(i.severity()))) {
      throw new CommandConflictException("CATEGORY_WORKFLOW_CONTRACT_MISMATCH", "Workflow override must keep the category's input contract and initial state");
    }
  }

  private WorkflowVersion requirePublishedWorkflow(UUID id) {
    return workflowVersions.findById(id).filter(v -> v.getStatus() == com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus.PUBLISHED)
        .orElseThrow(() -> new CommandConflictException("CATEGORY_WORKFLOW_NOT_PUBLISHED", "Workflow must be Published"));
  }

  private FormVersion requirePublishedForm(TicketCategoryVersion version) {
    return formVersions.findById(version.getFormVersionId()).filter(v -> "PUBLISHED".equals(v.getStatus()))
        .orElseThrow(() -> new CommandConflictException("CATEGORY_FORM_NOT_PUBLISHED", "Shared Form must be Published"));
  }

  private TicketCategory requireCategory(UUID id) { return categories.findById(id).orElseThrow(() -> new IllegalArgumentException("TicketCategory not found: " + id)); }
  private TicketCategoryVersion requirePublishedVersion(TicketCategory category) {
    if (category.getCurrentPublishedVersionId() == null) throw new CommandConflictException("CATEGORY_NOT_PUBLISHED", "Category has no Published version");
    return categoryVersions.findById(category.getCurrentPublishedVersionId()).filter(v -> "PUBLISHED".equals(v.getStatus()))
        .orElseThrow(() -> new CommandConflictException("CATEGORY_BINDING_INVALID", "Current CategoryVersion is not Published"));
  }

  public record OverrideCommand(UUID tenantId, UUID workflowVersionId, Long expectedLockVersion) {}
  public record BindingOverview(UUID categoryId, String categoryKey, String categoryName, FormSummary sharedForm, List<ScopeBindingView> scopes) {}
  public record FormSummary(UUID formId, String formKey, String name, UUID formVersionId, int versionNo) {}
  public record ScopeBindingView(String state, UUID tenantId, String tenantKey, String tenantName, UUID categoryVersionId, UUID formVersionId, String formName, int formVersionNo, UUID workflowVersionId, String workflowName, int workflowVersionNo, long lockVersion, boolean canEdit) {}
}
