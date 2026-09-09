package com.fpt.workflow.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting filter protecting sensitive workflow endpoints against brute-force, credential
 * stuffing, replay floods, and denial-of-service attempts.
 *
 * <p>Guarded paths:
 *
 * <ul>
 *   <li>File uploads: {@code /api/v1/files/upload}
 *   <li>Integration callbacks: {@code /api/v1/callbacks/**}
 *   <li>Operator actions: {@code /api/v1/operations/**}
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SensitiveApiRateLimitFilter extends OncePerRequestFilter {

  public static final int DEFAULT_MAX_REQUESTS_PER_WINDOW = 60;
  public static final long DEFAULT_WINDOW_SECONDS = 60;

  private final int maxRequestsPerWindow;
  private final long windowSeconds;
  private final Map<String, WindowCounter> requestCounts = new ConcurrentHashMap<>();

  public SensitiveApiRateLimitFilter() {
    this(DEFAULT_MAX_REQUESTS_PER_WINDOW, DEFAULT_WINDOW_SECONDS);
  }

  public SensitiveApiRateLimitFilter(int maxRequestsPerWindow, long windowSeconds) {
    this.maxRequestsPerWindow = maxRequestsPerWindow;
    this.windowSeconds = windowSeconds;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    // Only apply rate limiting to sensitive APIs
    return !(uri.startsWith("/api/v1/files/upload")
        || uri.startsWith("/api/v1/callbacks")
        || uri.startsWith("/api/v1/operations"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String clientKey = resolveClientKey(request);
    long currentWindow = Instant.now().getEpochSecond() / windowSeconds;
    String bucketKey = clientKey + ":" + currentWindow;

    WindowCounter counter =
        requestCounts.compute(
            bucketKey,
            (k, v) -> {
              if (v == null) {
                return new WindowCounter(currentWindow);
              }
              v.count.incrementAndGet();
              return v;
            });

    // Clean up old window entries periodically
    if (requestCounts.size() > 5000) {
      long minWindow = currentWindow - 2;
      requestCounts.entrySet().removeIf(entry -> entry.getValue().windowEpoch < minWindow);
    }

    if (counter.count.get() > maxRequestsPerWindow) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setHeader("Retry-After", String.valueOf(windowSeconds));
      response
          .getWriter()
          .write(
              "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded for sensitive endpoint. Please retry later.\"}");
      return;
    }

    filterChain.doFilter(request, response);
  }

  private String resolveClientKey(HttpServletRequest request) {
    String actorId = request.getHeader(ActorAuthenticationFilter.ACTOR_ID_HEADER);
    if (actorId != null && !actorId.isBlank()) {
      return "actor:" + actorId.trim();
    }
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isBlank()) {
      return "ip:" + xForwardedFor.split(",")[0].trim();
    }
    return "ip:" + request.getRemoteAddr();
  }

  private static final class WindowCounter {
    final long windowEpoch;
    final AtomicInteger count;

    WindowCounter(long windowEpoch) {
      this.windowEpoch = windowEpoch;
      this.count = new AtomicInteger(1);
    }
  }
}
