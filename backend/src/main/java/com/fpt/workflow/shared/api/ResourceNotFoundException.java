package com.fpt.workflow.shared.api;

import org.springframework.http.HttpStatus;

public final class ResourceNotFoundException extends ApiException {

  public ResourceNotFoundException(String code, String message) {
    super(HttpStatus.NOT_FOUND, code, message);
  }
}
