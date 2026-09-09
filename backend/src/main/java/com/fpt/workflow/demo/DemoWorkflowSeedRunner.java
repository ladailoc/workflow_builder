package com.fpt.workflow.demo;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Executes demo workflow bootstrap seeding upon application startup when enabled by configuration.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "platform.seed.demo.enabled", havingValue = "true")
public class DemoWorkflowSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoWorkflowSeedRunner.class);
  private final DemoWorkflowSeeder seeder;

  public DemoWorkflowSeedRunner(DemoWorkflowSeeder seeder) {
    this.seeder = Objects.requireNonNull(seeder, "seeder");
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("Triggering demo workflow bootstrap on application startup...");
    seeder.seedAll();
  }
}
