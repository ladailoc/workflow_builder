package com.fpt.workflow.runtime.binding;

public final class BindingException extends RuntimeException {

  private final String code;
  private final String target;

  public BindingException(String code, String target, String message) {
    super(message);
    this.code = code;
    this.target = target;
  }

  public String code() {
    return code;
  }

  public String target() {
    return target;
  }
}
