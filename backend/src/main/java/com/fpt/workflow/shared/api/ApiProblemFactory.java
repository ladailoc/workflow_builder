package com.fpt.workflow.shared.api;

import com.fpt.workflow.shared.time.PlatformClock;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class ApiProblemFactory {

  private final PlatformClock clock;

  public ApiProblemFactory(PlatformClock clock) {
    this.clock = clock;
  }

  public ApiProblem create(
      HttpStatus status,
      String code,
      String detail,
      List<ValidationError> errors,
      HttpServletRequest request) {
    return new ApiProblem(
        problemType(code),
        status.getReasonPhrase(),
        status.value(),
        detail,
        request.getRequestURI(),
        code,
        RequestCorrelationFilter.correlationId(request),
        RequestCorrelationFilter.requestId(request),
        clock.now(),
        List.copyOf(errors));
  }

  private static URI problemType(String code) {
    return URI.create(
        "urn:workflow-platform:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
  }
}
