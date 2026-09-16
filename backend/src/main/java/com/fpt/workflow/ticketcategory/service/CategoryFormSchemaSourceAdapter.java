package com.fpt.workflow.ticketcategory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.runtime.context.CategoryFormSchemaSource;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Bridges category ownership to the runtime context without making runtime depend on category. */
@Component
public final class CategoryFormSchemaSourceAdapter implements CategoryFormSchemaSource {
  private final TicketCategoryVersionRepository categories;
  private final FormVersionRepository forms;
  private final ObjectMapper mapper;

  public CategoryFormSchemaSourceAdapter(
      TicketCategoryVersionRepository categories, FormVersionRepository forms, ObjectMapper mapper) {
    this.categories = categories;
    this.forms = forms;
    this.mapper = mapper;
  }

  @Override
  public FormSchema load(UUID categoryVersionId) {
    if (categoryVersionId == null) return null;
    FormVersion form =
        categories
            .findById(categoryVersionId)
            .flatMap(version -> forms.findById(version.getFormVersionId()))
            .filter(version -> Set.of("PUBLISHED", "SUPERSEDED").contains(version.getStatus()))
            .orElse(null);
    if (form == null) return null;
    try {
      return mapper.treeToValue(
          form.getCompiledSchemaJson() != null
              ? form.getCompiledSchemaJson()
              : form.getSchemaJson(),
          FormSchema.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Published category form cannot be decoded", exception);
    }
  }
}
