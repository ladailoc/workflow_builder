package com.fpt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fpt.workflow.shared.PlatformConventions;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlatformConventionsTest {

  private final TimeZone originalTimeZone = TimeZone.getDefault();

  @AfterEach
  void restoreTimeZone() {
    TimeZone.setDefault(originalTimeZone);
  }

  @Test
  void configuresUtcAsTheJvmDefault() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));

    WorkflowPlatformApplication.configureRuntimeDefaults();

    assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    assertThat(PlatformConventions.PERSISTENCE_ZONE).isEqualTo(java.time.ZoneOffset.UTC);
  }

  @Test
  void exposesTheVersionedBusinessApiPrefix() {
    assertThat(PlatformConventions.API_PREFIX).isEqualTo("/api/v1");
  }
}
