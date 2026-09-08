package com.fpt.workflow.security.masking;

import org.springframework.stereotype.Component;

@Component
public final class DefaultSensitiveValueMasker implements SensitiveValueMasker {

  public static final String REDACTED = "[REDACTED]";

  @Override
  public String mask(String value) {
    return REDACTED;
  }
}
