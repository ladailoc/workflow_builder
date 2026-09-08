package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_versions")
public class WorkflowVersion {

  @Id private UUID id;

  @Column(name = "definition_id", nullable = false)
  private UUID definitionId;

  @Column(name = "version_no", nullable = false)
  private int versionNo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private WorkflowVersionStatus status;

  @Column(nullable = false)
  private long revision;

  @Column(length = 256)
  private String checksum;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "execution_package_json", columnDefinition = "jsonb")
  private JsonNode executionPackageJson;

  @Column(name = "based_on_version_id")
  private UUID basedOnVersionId;

  @Column(name = "rollback_of_version_id")
  private UUID rollbackOfVersionId;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "published_by")
  private UUID publishedBy;

  @Column(name = "published_at", columnDefinition = "timestamptz")
  private Instant publishedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected WorkflowVersion() {}

  private WorkflowVersion(
      UUID id,
      UUID definitionId,
      int versionNo,
      UUID basedOnVersionId,
      UUID rollbackOfVersionId,
      UUID createdBy,
      Instant createdAt) {
    if (versionNo < 1) {
      throw new IllegalArgumentException("versionNo must be positive");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
    this.versionNo = versionNo;
    this.status = WorkflowVersionStatus.DRAFT;
    this.basedOnVersionId = basedOnVersionId;
    this.rollbackOfVersionId = rollbackOfVersionId;
    if (id.equals(basedOnVersionId) || id.equals(rollbackOfVersionId)) {
      throw new IllegalArgumentException("Workflow version cannot reference itself");
    }
    this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static WorkflowVersion createDraft(
      UUID id,
      UUID definitionId,
      int versionNo,
      UUID basedOnVersionId,
      UUID rollbackOfVersionId,
      UUID createdBy,
      Instant createdAt) {
    return new WorkflowVersion(
        id, definitionId, versionNo, basedOnVersionId, rollbackOfVersionId, createdBy, createdAt);
  }

  public void updateDraft(long expectedRevision, String checksum, JsonNode executionPackageJson) {
    requireDraft();
    if (revision != expectedRevision) {
      throw new StaleDraftRevisionException(expectedRevision, revision);
    }
    this.checksum = checksum == null || checksum.isBlank() ? null : checksum.trim();
    this.executionPackageJson =
        DefinitionValues.nullableObject(executionPackageJson, "executionPackageJson");
    this.revision = Math.addExact(revision, 1);
  }

  public void recordGraphMutation(long expectedRevision) {
    requireDraft();
    if (revision != expectedRevision) {
      throw new StaleDraftRevisionException(expectedRevision, revision);
    }
    this.checksum = null;
    this.executionPackageJson = null;
    this.revision = Math.addExact(revision, 1);
  }

  public void requireDraft() {
    if (status != WorkflowVersionStatus.DRAFT) {
      throw new IllegalStateException("Only DRAFT WorkflowVersion is mutable");
    }
  }

  public void publish(
      long expectedRevision,
      String checksum,
      JsonNode executionPackageJson,
      UUID publishedBy,
      Instant publishedAt) {
    requireDraft();
    if (revision != expectedRevision) {
      throw new StaleDraftRevisionException(expectedRevision, revision);
    }
    if (checksum == null || checksum.isBlank()) {
      throw new IllegalArgumentException("Published checksum must not be blank");
    }
    this.checksum = checksum.trim();
    this.executionPackageJson =
        DefinitionValues.nullableObject(executionPackageJson, "executionPackageJson");
    if (this.executionPackageJson == null) {
      throw new IllegalArgumentException("Published execution package is required");
    }
    this.publishedBy = Objects.requireNonNull(publishedBy, "publishedBy");
    this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
    this.status = WorkflowVersionStatus.PUBLISHED;
  }

  public void supersede() {
    if (status != WorkflowVersionStatus.PUBLISHED) {
      throw new IllegalStateException("Only PUBLISHED WorkflowVersion can be superseded");
    }
    status = WorkflowVersionStatus.SUPERSEDED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDefinitionId() {
    return definitionId;
  }

  public int getVersionNo() {
    return versionNo;
  }

  public WorkflowVersionStatus getStatus() {
    return status;
  }

  public long getRevision() {
    return revision;
  }

  public String getChecksum() {
    return checksum;
  }

  public JsonNode getExecutionPackageJson() {
    return executionPackageJson == null ? null : executionPackageJson.deepCopy();
  }

  public UUID getBasedOnVersionId() {
    return basedOnVersionId;
  }

  public UUID getRollbackOfVersionId() {
    return rollbackOfVersionId;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getPublishedBy() {
    return publishedBy;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
