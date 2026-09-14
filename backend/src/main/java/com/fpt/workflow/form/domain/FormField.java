package com.fpt.workflow.form.domain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="form_fields")
public class FormField {
  @Id private UUID id;
  @Column(name="form_version_id",nullable=false) private UUID formVersionId;
  @Column(name="field_key",nullable=false,length=128) private String fieldKey;
  @Column(nullable=false,length=256) private String label;
  @Column(nullable=false) private int ordinal;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="type_json",nullable=false,columnDefinition="jsonb") private TypeDescriptor type;
  @Column(nullable=false) private boolean sensitive;
  @Column(name="semantic_tag",length=128) private String semanticTag;
  protected FormField(){}
  public static FormField create(UUID id, UUID formVersionId, String fieldKey, String label,
      int ordinal, TypeDescriptor type, boolean sensitive, String semanticTag) {
    FormField value = new FormField();
    value.id = java.util.Objects.requireNonNull(id, "id");
    value.formVersionId = java.util.Objects.requireNonNull(formVersionId, "formVersionId");
    value.fieldKey = FormValues.key(fieldKey);
    value.label = label == null || label.isBlank() ? fieldKey : label.trim();
    value.ordinal = ordinal;
    value.type = java.util.Objects.requireNonNull(type, "type");
    value.sensitive = sensitive;
    value.semanticTag = semanticTag == null || semanticTag.isBlank() ? null : semanticTag.trim();
    return value;
  }
  public UUID getId(){return id;} public UUID getFormVersionId(){return formVersionId;} public String getFieldKey(){return fieldKey;}
  public String getLabel(){return label;} public int getOrdinal(){return ordinal;} public TypeDescriptor getType(){return type;} public boolean isSensitive(){return sensitive;} public String getSemanticTag(){return semanticTag;}
}
