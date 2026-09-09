package com.fpt.workflow.operations.config;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Validates critical production configuration invariants on startup. Ensures UTC timezone,
 * externalized database credentials, and absence of insecure defaults. Guarantees that secret
 * values are NEVER logged.
 */
@Component
public class ProductionConfigurationValidator implements ApplicationRunner, Ordered {

  private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationValidator.class);

  private static final Set<String> INSECURE_PASSWORDS =
      Set.of("workflow123", "password", "admin", "root", "123456", "test", "secret");

  private final Environment environment;

  public ProductionConfigurationValidator(Environment environment) {
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public void run(ApplicationArguments args) {
    validateTimezone();
    validateEnvironment(this.environment);
  }

  public void validateTimezone() {
    String jvmTz = TimeZone.getDefault().getID();
    ZoneId systemZone = ZoneId.systemDefault().normalized();
    if (!"UTC".equals(jvmTz) && !ZoneOffset.UTC.equals(systemZone)) {
      log.warn("JVM default timezone was {}, forcing to UTC", jvmTz);
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
  }

  public void validateEnvironment(Environment env) {
    List<String> activeProfiles = Arrays.asList(env.getActiveProfiles());

    boolean isProd = activeProfiles.contains("prod") || activeProfiles.contains("production");
    boolean isStaging = activeProfiles.contains("staging");

    if (!isProd && !isStaging) {
      log.info("Running in development/test profile: {}", activeProfiles);
      return;
    }

    String dbUrl = env.getProperty("spring.datasource.url");
    if (dbUrl == null || dbUrl.isBlank()) {
      dbUrl = env.getProperty("DATABASE_URL");
    }

    String dbUser = env.getProperty("spring.datasource.username");
    if (dbUser == null || dbUser.isBlank()) {
      dbUser = env.getProperty("DATABASE_USERNAME");
    }

    String dbPassword = env.getProperty("spring.datasource.password");
    if (dbPassword == null || dbPassword.isBlank()) {
      dbPassword = env.getProperty("DATABASE_PASSWORD");
    }

    if (dbUrl == null || dbUrl.isBlank()) {
      throw new IllegalStateException("Database URL is required in staging/production");
    }
    if (dbUser == null || dbUser.isBlank()) {
      throw new IllegalStateException("Database username is required in staging/production");
    }
    if (dbPassword == null || dbPassword.isBlank()) {
      throw new IllegalStateException("Database password is required in staging/production");
    }

    if (isProd) {
      if (INSECURE_PASSWORDS.contains(dbPassword.trim().toLowerCase())) {
        throw new IllegalStateException(
            "Production profile must not use default or trivial development database credentials");
      }

      String corsOrigins = env.getProperty("platform.security.cors.allowed-origins");
      if (corsOrigins != null && corsOrigins.contains("*")) {
        throw new IllegalStateException(
            "Production profile must not allow wildcard '*' CORS origins");
      }
    }

    String sanitizedUrl = sanitizeJdbcUrl(dbUrl);
    String maskedUser = mask(dbUser);
    log.info(
        "Production configuration validated successfully [profiles={}, dbUrl={}, dbUser={}, secretsMasked=true]",
        activeProfiles,
        sanitizedUrl,
        maskedUser);
  }

  private static String sanitizeJdbcUrl(String url) {
    if (url == null) return "null";
    // Remove user:password embedded in URLs like postgresql://user:pass@host
    return url.replaceAll("://([^:]+):([^@]+)@", "://$1:***@");
  }

  private static String mask(String value) {
    if (value == null || value.length() <= 2) {
      return "***";
    }
    return value.charAt(0) + "***" + value.charAt(value.length() - 1);
  }
}
