package com.fpt.workflow.slanotification.service;

import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import org.springframework.stereotype.Component;

/**
 * Durable in-app delivery. The dispatch row is the inbox record, so delivery only needs to
 * complete the job; the read API exposes the immutable recipient/template/payload snapshots.
 */
@Component
public final class InAppNotificationChannel implements NotificationChannel {

  @Override
  public String channel() {
    return "IN_APP";
  }

  @Override
  public void send(NotificationDispatch dispatch) {
    // No external side effect is required. NotificationDispatch is already the durable inbox row.
  }
}
