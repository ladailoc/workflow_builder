package com.fpt.workflow.security.masking;

/** Masks a value after sensitive-field metadata or policy has classified it as sensitive. */
@FunctionalInterface
public interface SensitiveValueMasker {

  String mask(String value);
}
