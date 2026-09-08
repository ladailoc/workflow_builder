package com.fpt.workflow.definition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "workflow_validation_runs")
public class WorkflowValidationRun {

  @Id private UUID id;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(nullable = false)
  private long revision;

  @Column(name = "definition_checksum", nullable = false, length = 256)
  private String definitionChecksum;

  @Column(nullable = false)
  private boolean valid;

  @Column(nullable = false)
  private boolean publishable;

  @Column(name = "error_count", nullable = false)
  private int errorCount;

  @Column(name = "warning_count", nullable = false)
  private int warningCount;

  @Column(name = "info_count", nullable = false)
  private int infoCount;

  @Column(name = "validated_by", nullable = false)
  private UUID validatedBy;

  @Column(name = "validated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant validatedAt;

  protected WorkflowValidationRun() {}

  private WorkflowValidationRun(
      UUID id,
      UUID workflowVersionId,
      long revision,
      String definitionChecksum,
      boolean valid,
      boolean publishable,
      int errorCount,
      int warningCount,
      int infoCount,
      UUID validatedBy,
      Instant validatedAt) {
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    if (errorCount < 0 || warningCount < 0 || infoCount < 0) {
      throw new IllegalArgumentException("validation summary counts must not be negative");
    }
    if (valid != (errorCount == 0)) {
      throw new IllegalArgumentException("valid must match whether errorCount is zero");
    }
    if (publishable && !valid) {
      throw new IllegalArgumentException("publishable validation run must be valid");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.revision = revision;
    this.definitionChecksum =
        ValidationValues.requiredText(definitionChecksum, "definitionChecksum");
    this.valid = valid;
    this.publishable = publishable;
    this.errorCount = errorCount;
    this.warningCount = warningCount;
    this.infoCount = infoCount;
    this.validatedBy = Objects.requireNonNull(validatedBy, "validatedBy");
    this.validatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
  }

  public static WorkflowValidationRun create(
      UUID id,
      UUID workflowVersionId,
      long revision,
      String definitionChecksum,
      boolean valid,
      boolean publishable,
      int errorCount,
      int warningCount,
      int infoCount,
      UUID validatedBy,
      Instant validatedAt) {
    return new WorkflowValidationRun(
        id,
        workflowVersionId,
        revision,
        definitionChecksum,
        valid,
        publishable,
        errorCount,
        warningCount,
        infoCount,
        validatedBy,
        validatedAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public long getRevision() {
    return revision;
  }

  public String getDefinitionChecksum() {
    return definitionChecksum;
  }

  public boolean isValid() {
    return valid;
  }

  public boolean isPublishable() {
    return publishable;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public int getWarningCount() {
    return warningCount;
  }

  public int getInfoCount() {
    return infoCount;
  }

  public UUID getValidatedBy() {
    return validatedBy;
  }

  public Instant getValidatedAt() {
    return validatedAt;
  }
}
