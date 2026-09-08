package com.fpt.workflow.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.shared.api.ApiProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public final class SecurityProblemWriter {

  private final ObjectMapper objectMapper;
  private final ApiProblemFactory problemFactory;

  public SecurityProblemWriter(ObjectMapper objectMapper, ApiProblemFactory problemFactory) {
    this.objectMapper = objectMapper;
    this.problemFactory = problemFactory;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(),
        problemFactory.create(status, code, detail, List.of(), request));
  }
}
