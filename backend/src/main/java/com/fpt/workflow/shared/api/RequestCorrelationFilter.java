package com.fpt.workflow.shared.api;

import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {

  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String CORRELATION_ID_ATTRIBUTE =
      RequestCorrelationFilter.class.getName() + ".correlationId";
  public static final String REQUEST_ID_ATTRIBUTE =
      RequestCorrelationFilter.class.getName() + ".requestId";

  private final UuidGenerator uuidGenerator;

  public RequestCorrelationFilter(UuidGenerator uuidGenerator) {
    this.uuidGenerator = uuidGenerator;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
    String requestId = uuidGenerator.generate().toString();

    request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    response.setHeader(REQUEST_ID_HEADER, requestId);

    try (MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable("correlationId", correlationId);
        MDC.MDCCloseable ignoredRequest = MDC.putCloseable("requestId", requestId)) {
      filterChain.doFilter(request, response);
    }
  }

  private String resolveCorrelationId(String candidate) {
    if (candidate != null) {
      try {
        return CorrelationId.parse(candidate).toString();
      } catch (IllegalArgumentException ignored) {
        // Do not propagate arbitrary client-controlled values into logs or response headers.
      }
    }
    return CorrelationId.generate(uuidGenerator).toString();
  }

  public static String correlationId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(CORRELATION_ID_ATTRIBUTE));
  }

  public static String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(REQUEST_ID_ATTRIBUTE));
  }
}
