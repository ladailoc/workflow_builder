package com.fpt.workflow.definition.validation;

import java.util.UUID;

public record ControlledCycleIssue(
    String code, UUID resourceId, String fieldPath, String message) {}
