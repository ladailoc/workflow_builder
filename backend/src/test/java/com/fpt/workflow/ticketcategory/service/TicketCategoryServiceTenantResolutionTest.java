package com.fpt.workflow.ticketcategory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.ticketcategory.domain.TicketCategory;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryVersion;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryWorkflowBinding;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryMappingRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryWorkflowBindingRepository;
import com.fpt.workflow.tenant.service.TenantAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TicketCategoryServiceTenantResolutionTest {
  private final TicketCategoryRepository categories = mock(TicketCategoryRepository.class);
  private final TicketCategoryVersionRepository versions = mock(TicketCategoryVersionRepository.class);
  private final FormVersionRepository forms = mock(FormVersionRepository.class);
  private final WorkflowVersionRepository workflows = mock(WorkflowVersionRepository.class);
  private final TicketCategoryMappingRepository mappings = mock(TicketCategoryMappingRepository.class);
  private final TicketCategoryWorkflowBindingRepository bindings = mock(TicketCategoryWorkflowBindingRepository.class);
  private final TenantAccessService tenantAccess = mock(TenantAccessService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final UUID categoryId = UUID.randomUUID();
  private final UUID categoryVersionId = UUID.randomUUID();
  private final UUID formVersionId = UUID.randomUUID();
  private final UUID defaultWorkflowId = UUID.randomUUID();
  private final UUID overrideWorkflowId = UUID.randomUUID();
  private final UUID tenantA = UUID.randomUUID();
  private final UUID tenantB = UUID.randomUUID();
  private TicketCategoryService service;

  @BeforeEach
  void setUp() {
    service = new TicketCategoryService(categories, versions, forms, workflows, mappings, bindings, tenantAccess, mapper);
    TicketCategory category = mock(TicketCategory.class);
    when(category.getId()).thenReturn(categoryId);
    when(category.getKey()).thenReturn("PURCHASE");
    when(category.getName()).thenReturn("Mua sắm");
    when(category.getDescription()).thenReturn("Yêu cầu mua sắm");
    when(category.getIcon()).thenReturn(null);
    when(category.getLifecycle()).thenReturn("ACTIVE");
    when(category.getCurrentPublishedVersionId()).thenReturn(categoryVersionId);
    when(categories.findByKey("PURCHASE")).thenReturn(Optional.of(category));

    TicketCategoryVersion version = mock(TicketCategoryVersion.class);
    when(version.getId()).thenReturn(categoryVersionId);
    when(version.getStatus()).thenReturn("PUBLISHED");
    when(version.getFormVersionId()).thenReturn(formVersionId);
    when(version.getWorkflowVersionId()).thenReturn(defaultWorkflowId);
    when(version.getChecksum()).thenReturn("category-checksum");
    when(version.getMappingChecksum()).thenReturn("mapping-checksum");
    when(versions.findById(categoryVersionId)).thenReturn(Optional.of(version));

    FormVersion form = mock(FormVersion.class);
    when(form.getId()).thenReturn(formVersionId);
    when(form.getStatus()).thenReturn("PUBLISHED");
    when(form.getVersionNo()).thenReturn(1);
    when(form.getChecksum()).thenReturn("form-checksum");
    when(form.getSchemaJson()).thenReturn(schema());
    when(form.getCompiledSchemaJson()).thenReturn(null);
    when(forms.findById(formVersionId)).thenReturn(Optional.of(form));

    WorkflowVersion defaultWorkflow = workflow(defaultWorkflowId, 1, "default-checksum");
    WorkflowVersion overrideWorkflow = workflow(overrideWorkflowId, 2, "override-checksum");
    when(workflows.findById(defaultWorkflowId)).thenReturn(Optional.of(defaultWorkflow));
    when(workflows.findById(overrideWorkflowId)).thenReturn(Optional.of(overrideWorkflow));
    when(bindings.findByTicketCategoryIdAndTenantId(categoryId, null)).thenReturn(Optional.empty());
  }

  @Test
  void usesDefaultWorkflowWhenNoTenantIsSelected() {
    var contract = service.resolvePublishedForCreate("PURCHASE");
    assertThat(contract.workflowVersionId()).isEqualTo(defaultWorkflowId);
    assertThat(contract.bindingScope()).isEqualTo("DEFAULT");
    verifyNoInteractions(tenantAccess);
  }

  @Test
  void usesTenantOverrideBeforeDefault() {
    TicketCategoryWorkflowBinding override = binding(tenantA, overrideWorkflowId);
    when(bindings.findByTicketCategoryIdAndTenantId(categoryId, tenantA)).thenReturn(Optional.of(override));
    var contract = service.resolvePublishedForCreate("PURCHASE", tenantA);
    assertThat(contract.workflowVersionId()).isEqualTo(overrideWorkflowId);
    assertThat(contract.bindingScope()).isEqualTo("OVERRIDE");
    verify(tenantAccess).requireUsable(tenantA);
  }

  @Test
  void fallsBackForTenantWithoutOverride() {
    when(bindings.findByTicketCategoryIdAndTenantId(categoryId, tenantA)).thenReturn(Optional.empty());
    when(bindings.findByTicketCategoryIdAndTenantId(categoryId, tenantB)).thenReturn(Optional.empty());
    assertThat(service.resolvePublishedForCreate("PURCHASE", tenantA).workflowVersionId()).isEqualTo(defaultWorkflowId);
    assertThat(service.resolvePublishedForCreate("PURCHASE", tenantB).workflowVersionId()).isEqualTo(defaultWorkflowId);
    verify(tenantAccess).requireUsable(tenantA);
    verify(tenantAccess).requireUsable(tenantB);
  }

  @Test
  void keepsSharedFormWhenTenantWorkflowChanges() {
    TicketCategoryWorkflowBinding override = binding(tenantA, overrideWorkflowId);
    when(bindings.findByTicketCategoryIdAndTenantId(categoryId, tenantA)).thenReturn(Optional.of(override));
    var defaultContract = service.resolvePublishedForCreate("PURCHASE");
    var overrideContract = service.resolvePublishedForCreate("PURCHASE", tenantA);
    assertThat(overrideContract.formVersionId()).isEqualTo(defaultContract.formVersionId());
    assertThat(overrideContract.formChecksum()).isEqualTo(defaultContract.formChecksum());
  }

  @Test
  void reportsMissingDefaultCategoryVersion() {
    TicketCategory category = mock(TicketCategory.class);
    when(category.getLifecycle()).thenReturn("ACTIVE");
    when(category.getCurrentPublishedVersionId()).thenReturn(null);
    when(categories.findByKey("EMPTY")).thenReturn(Optional.of(category));
    assertThatThrownBy(() -> service.resolvePublishedForCreate("EMPTY"))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("no Published CategoryVersion");
  }

  @Test
  void rejectsUnpublishedResolvedWorkflow() {
    WorkflowVersion unpublished = workflow(overrideWorkflowId, 2, "override-checksum");
    when(unpublished.getStatus()).thenReturn(WorkflowVersionStatus.DRAFT);
    TicketCategoryWorkflowBinding override = binding(tenantA, overrideWorkflowId);
    when(bindings.findByTicketCategoryIdAndTenantId(categoryId, tenantA)).thenReturn(Optional.of(override));
    when(workflows.findById(overrideWorkflowId)).thenReturn(Optional.of(unpublished));
    assertThatThrownBy(() -> service.resolvePublishedForCreate("PURCHASE", tenantA))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("not Published");
  }

  @Test
  void deniesTenantWithoutMembership() {
    doThrow(new org.springframework.security.access.AccessDeniedException("TENANT_ACCESS_DENIED"))
        .when(tenantAccess).requireUsable(tenantA);
    assertThatThrownBy(() -> service.resolvePublishedForCreate("PURCHASE", tenantA))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
        .hasMessageContaining("TENANT_ACCESS_DENIED");
  }

  @Test
  void defaultOverloadRemainsBackwardCompatible() {
    assertThat(service.resolvePublishedForCreate("PURCHASE").tenantId()).isNull();
    assertThat(service.resolvePublishedForCreate("PURCHASE").workflowVersionNo()).isEqualTo(1);
  }

  private WorkflowVersion workflow(UUID id, int versionNo, String checksum) {
    WorkflowVersion workflow = mock(WorkflowVersion.class);
    when(workflow.getId()).thenReturn(id);
    when(workflow.getVersionNo()).thenReturn(versionNo);
    when(workflow.getChecksum()).thenReturn(checksum);
    when(workflow.getStatus()).thenReturn(WorkflowVersionStatus.PUBLISHED);
    return workflow;
  }

  private TicketCategoryWorkflowBinding binding(UUID tenantId, UUID workflowId) {
    TicketCategoryWorkflowBinding binding = mock(TicketCategoryWorkflowBinding.class);
    when(binding.getTenantId()).thenReturn(tenantId);
    when(binding.getWorkflowVersionId()).thenReturn(workflowId);
    return binding;
  }

  private com.fasterxml.jackson.databind.JsonNode schema() {
    return mapper.createObjectNode().put("formKey", "purchase").put("formType", "TICKET_FORM").set("fields", JsonNodeFactory.instance.arrayNode());
  }
}
