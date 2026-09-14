package com.fpt.workflow.form.api;

import com.fpt.workflow.shared.api.UnprocessableCommandException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicitly rejects the legacy ambiguous Form-as-process-selector shape. */
@RestController
@RequestMapping("/api/v1/forms")
@PreAuthorize("isAuthenticated()")
public class FormTicketSelectionController {
  @PostMapping("/{formVersionId}/tickets")
  public void rejectFormOnlyCreate(@PathVariable UUID formVersionId) {
    throw new UnprocessableCommandException(
        "CATEGORY_KEY_REQUIRED",
        "A formVersionId cannot select a workflow; choose a business intent/categoryKey first");
  }
}
