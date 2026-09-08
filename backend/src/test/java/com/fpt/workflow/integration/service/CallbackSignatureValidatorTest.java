package com.fpt.workflow.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallbackSignatureValidatorTest {

  private CallbackSignatureValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CallbackSignatureValidator();
  }

  @Test
  void testValidHexSignature() {
    String secret = "super-secret-key-123";
    String timestamp = Instant.now().toString();
    String payload = "{\"result\":\"ok\",\"score\":100}";

    String signature = validator.computeSignature(secret, timestamp, payload);

    assertThat(validator.isValid(secret, timestamp, payload, signature)).isTrue();
    assertThat(validator.isValid(secret, timestamp, payload, "sha256=" + signature)).isTrue();
  }

  @Test
  void testValidBase64Signature() {
    String secret = "super-secret-key-456";
    String timestamp = Instant.now().toString();
    String payload = "{\"paymentStatus\":\"SUCCESS\"}";

    String base64Sig = validator.computeBase64Signature(secret, timestamp, payload);

    assertThat(validator.isValid(secret, timestamp, payload, base64Sig)).isTrue();
  }

  @Test
  void testTamperedPayload_rejected() {
    String secret = "super-secret-key-123";
    String timestamp = Instant.now().toString();
    String payload = "{\"amount\":100}";

    String signature = validator.computeSignature(secret, timestamp, payload);

    assertThat(validator.isValid(secret, timestamp, "{\"amount\":999}", signature)).isFalse();
  }

  @Test
  void testTamperedTimestamp_rejected() {
    String secret = "super-secret-key-123";
    String timestamp = "2026-09-08T10:00:00Z";
    String payload = "{\"amount\":100}";

    String signature = validator.computeSignature(secret, timestamp, payload);

    assertThat(validator.isValid(secret, "2026-09-08T10:05:00Z", payload, signature)).isFalse();
  }

  @Test
  void testWrongSecret_rejected() {
    String secret1 = "secret-1";
    String secret2 = "secret-2";
    String timestamp = Instant.now().toString();
    String payload = "{\"amount\":100}";

    String signature = validator.computeSignature(secret1, timestamp, payload);

    assertThat(validator.isValid(secret2, timestamp, payload, signature)).isFalse();
  }

  @Test
  void testNullParameters_safeRejection() {
    assertThat(validator.isValid(null, "time", "body", "sig")).isFalse();
    assertThat(validator.isValid("secret", "time", null, "sig")).isFalse();
    assertThat(validator.isValid("secret", "time", "body", null)).isFalse();
  }
}
