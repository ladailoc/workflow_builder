package com.fpt.workflow.shared.api;

import org.springframework.http.HttpStatus;

public final class UnprocessableCommandException extends ApiException {

  public UnprocessableCommandException(String code, String message) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }
}
