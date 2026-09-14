package com.fpt.workflow.resolver.participant;

/** Thrown when dynamic participant resolution fails and OnMissing policy is FAIL_NODE. */
public class ParticipantResolutionException extends RuntimeException {

  public ParticipantResolutionException(String message) {
    super(message);
  }

  public ParticipantResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
