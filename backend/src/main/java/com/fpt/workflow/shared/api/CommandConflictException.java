package com.fpt.workflow.shared.api;

import org.springframework.http.HttpStatus;

/** Invalid-state, stale-command, or business-precondition conflict for a command endpoint. */
public final class CommandConflictException extends ApiException {

  public CommandConflictException(String code, String message) {
    super(HttpStatus.CONFLICT, code, message);
  }
}
