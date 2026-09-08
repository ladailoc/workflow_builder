package com.fpt.workflow.shared.api;

import org.springframework.http.HttpStatus;

public final class BadRequestException extends ApiException {

  public BadRequestException(String code, String message) {
    super(HttpStatus.BAD_REQUEST, code, message);
  }
}
