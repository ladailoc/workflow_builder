package com.fpt.workflow.security.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final SecurityProblemWriter problemWriter;

  public ProblemAccessDeniedHandler(SecurityProblemWriter problemWriter) {
    this.problemWriter = problemWriter;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    problemWriter.write(
        request,
        response,
        HttpStatus.FORBIDDEN,
        "FORBIDDEN",
        "The authenticated actor is not allowed to perform this operation.");
  }
}
