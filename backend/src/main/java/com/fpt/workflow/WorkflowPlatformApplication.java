package com.fpt.workflow;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WorkflowPlatformApplication {

  public static void main(String[] args) {
    configureRuntimeDefaults();
    SpringApplication.run(WorkflowPlatformApplication.class, args);
  }

  static void configureRuntimeDefaults() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  }
}
