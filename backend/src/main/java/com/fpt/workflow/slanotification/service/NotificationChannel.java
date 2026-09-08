package com.fpt.workflow.slanotification.service;

import com.fpt.workflow.slanotification.domain.NotificationDispatch;

public interface NotificationChannel {
  String channel();

  void send(NotificationDispatch dispatch);
}
