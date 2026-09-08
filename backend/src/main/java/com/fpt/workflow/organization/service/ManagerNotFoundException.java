package com.fpt.workflow.organization.service;

/** Thrown when a manager cannot be resolved for an employee via the organization hierarchy. */
public class ManagerNotFoundException extends RuntimeException {

  public ManagerNotFoundException(String message) {
    super(message);
  }
}
