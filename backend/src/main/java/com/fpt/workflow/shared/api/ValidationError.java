package com.fpt.workflow.shared.api;

/** Field or object validation failure. Rejected values are deliberately not exposed. */
public record ValidationError(String field, String code, String message) {}
