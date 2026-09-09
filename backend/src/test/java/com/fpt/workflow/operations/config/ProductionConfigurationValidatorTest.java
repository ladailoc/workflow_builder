package com.fpt.workflow.operations.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTest {

  private final TimeZone originalTz = TimeZone.getDefault();

  @AfterEach
  void tearDown() {
    TimeZone.setDefault(originalTz);
  }

  @Test
  void enforcesUtcTimezoneWhenNotUtc() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    MockEnvironment env = new MockEnvironment();
    ProductionConfigurationValidator validator = new ProductionConfigurationValidator(env);

    validator.validateTimezone();

    assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
  }

  @Test
  void allowsDevProfileWithoutProductionSecrets() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("dev");
    ProductionConfigurationValidator validator = new ProductionConfigurationValidator(env);

    assertThatCode(() -> validator.validateEnvironment(env)).doesNotThrowAnyException();
  }

  @Test
  void throwsInStagingWhenDatabaseUrlIsMissing() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("staging");
    ProductionConfigurationValidator validator = new ProductionConfigurationValidator(env);

    assertThatThrownBy(() -> validator.validateEnvironment(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Database URL is required");
  }

  @Test
  void throwsInProdWhenPasswordIsInsecureDefault() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    env.setProperty("spring.datasource.url", "jdbc:postgresql://prod-db:5432/workflow");
    env.setProperty("spring.datasource.username", "workflow_prod");
    env.setProperty("spring.datasource.password", "workflow123");
    ProductionConfigurationValidator validator = new ProductionConfigurationValidator(env);

    assertThatThrownBy(() -> validator.validateEnvironment(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("default or trivial development database credentials");
  }

  @Test
  void throwsInProdWhenCorsIsWildcard() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    env.setProperty("spring.datasource.url", "jdbc:postgresql://prod-db:5432/workflow");
    env.setProperty("spring.datasource.username", "workflow_prod");
    env.setProperty("spring.datasource.password", "strong_prod_pass_9921#");
    env.setProperty("platform.security.cors.allowed-origins", "*");
    ProductionConfigurationValidator validator = new ProductionConfigurationValidator(env);

    assertThatThrownBy(() -> validator.validateEnvironment(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wildcard '*' CORS origins");
  }

  @Test
  void passesInProdWhenAllInvariantsAreSatisfied() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    env.setProperty("spring.datasource.url", "jdbc:postgresql://prod-db:5432/workflow");
    env.setProperty("spring.datasource.username", "workflow_prod");
    env.setProperty("spring.datasource.password", "strong_prod_pass_9921#");
    env.setProperty("platform.security.cors.allowed-origins", "https://workflow.enterprise.com");
    ProductionConfigurationValidator validator = new ProductionConfigurationValidator(env);

    assertThatCode(() -> validator.validateEnvironment(env)).doesNotThrowAnyException();
  }
}
