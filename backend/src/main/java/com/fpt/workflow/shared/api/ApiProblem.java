package com.fpt.workflow.shared.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/** RFC 9457-compatible error representation with stable platform extensions. */
public record ApiProblem(
    URI type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String correlationId,
    String requestId,
    Instant timestamp,
    List<ValidationError> errors) {}
