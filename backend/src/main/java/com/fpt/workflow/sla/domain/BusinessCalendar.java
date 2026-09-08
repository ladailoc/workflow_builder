package com.fpt.workflow.sla.domain;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "business_calendars")
public class BusinessCalendar {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 128)
  private String key;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected BusinessCalendar() {}

  public static BusinessCalendar create(
      UUID id, String key, String name, ZoneId zone, Instant now) {
    BusinessCalendar c = new BusinessCalendar();
    c.id = Objects.requireNonNull(id);
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
    c.key = key.trim();
    c.name = name.trim();
    c.timezone = Objects.requireNonNull(zone).getId();
    c.status = "ACTIVE";
    c.createdAt = now;
    c.updatedAt = now;
    return c;
  }

  public UUID getId() {
    return id;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public ZoneId getZoneId() {
    return ZoneId.of(timezone);
  }

  public String getStatus() {
    return status;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
