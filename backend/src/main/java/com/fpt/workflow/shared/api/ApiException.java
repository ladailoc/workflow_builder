package com.fpt.workflow.shared.api;

import org.springframework.http.HttpStatus;

public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  protected ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public final HttpStatus status() {
    return status;
  }

  public final String code() {
    return code;
  }
}
