package com.fpt.workflow.slanotification.service;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NotificationChannelRegistry {
  private final Map<String, NotificationChannel> channels;

  public NotificationChannelRegistry(List<NotificationChannel> providers) {
    Map<String, NotificationChannel> map = new HashMap<>();
    for (NotificationChannel p : providers) {
      String key = p.channel().trim().toUpperCase(Locale.ROOT);
      if (map.putIfAbsent(key, p) != null)
        throw new IllegalStateException("Duplicate notification channel: " + key);
    }
    channels = Map.copyOf(map);
  }

  public NotificationChannel require(String channel) {
    NotificationChannel value = channels.get(channel.trim().toUpperCase(Locale.ROOT));
    if (value == null) throw new IllegalStateException("Unknown notification channel: " + channel);
    return value;
  }
}
