package com.fpt.workflow.slanotification.domain;

public enum NotificationDispatchStatus {
  READY,
  SENDING,
  SENT,
  FAILED,
  DEAD,
  CANCELLED
}
