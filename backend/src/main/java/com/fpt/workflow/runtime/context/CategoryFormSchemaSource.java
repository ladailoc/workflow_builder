package com.fpt.workflow.runtime.context;

import com.fpt.workflow.form.engine.FormSchema;
import java.util.UUID;

/** Runtime port for resolving an immutable category-bound ticket form schema. */
@FunctionalInterface
public interface CategoryFormSchemaSource {
  FormSchema load(UUID categoryVersionId);
}
