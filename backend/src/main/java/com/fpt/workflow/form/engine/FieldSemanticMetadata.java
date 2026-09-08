package com.fpt.workflow.form.engine;

public record FieldSemanticMetadata(
    boolean participantCapable,
    boolean businessSubject,
    boolean filterable,
    boolean reportable,
    boolean searchable) {

  public static FieldSemanticMetadata none() {
    return new FieldSemanticMetadata(false, false, false, false, false);
  }
}
