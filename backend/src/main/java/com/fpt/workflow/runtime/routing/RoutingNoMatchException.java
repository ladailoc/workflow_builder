package com.fpt.workflow.runtime.routing;

public final class RoutingNoMatchException extends RuntimeException {

  public static final String CODE = "ROUTING_NO_MATCH";

  public RoutingNoMatchException(String message) {
    super(message);
  }

  public String code() {
    return CODE;
  }
}
