package com.fpt.workflow.integration.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class CallbackSignatureValidator {

  private static final String HMAC_SHA256 = "HmacSHA256";

  public boolean isValid(String secret, String timestamp, String rawPayload, String signature) {
    if (secret == null || signature == null || rawPayload == null) {
      return false;
    }
    try {
      byte[] expectedBytes = computeHmacBytes(secret, timestamp, rawPayload);
      byte[] providedBytes = parseSignature(signature);
      if (providedBytes == null) {
        return false;
      }
      return MessageDigest.isEqual(expectedBytes, providedBytes);
    } catch (Exception ex) {
      return false;
    }
  }

  public String computeSignature(String secret, String timestamp, String rawPayload) {
    byte[] bytes = computeHmacBytes(secret, timestamp, rawPayload);
    return HexFormat.of().formatHex(bytes);
  }

  public String computeBase64Signature(String secret, String timestamp, String rawPayload) {
    byte[] bytes = computeHmacBytes(secret, timestamp, rawPayload);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private byte[] computeHmacBytes(String secret, String timestamp, String rawPayload) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      SecretKeySpec secretKey =
          new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
      mac.init(secretKey);

      String contentToSign =
          (timestamp != null && !timestamp.isBlank()) ? timestamp + "." + rawPayload : rawPayload;
      return mac.doFinal(contentToSign.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new IllegalStateException("Failed to calculate HMAC-SHA256", ex);
    }
  }

  private byte[] parseSignature(String signature) {
    String trimmed = signature.trim();
    if (trimmed.startsWith("sha256=")) {
      trimmed = trimmed.substring("sha256=".length());
    }
    // Attempt hex decode first
    try {
      return HexFormat.of().parseHex(trimmed);
    } catch (IllegalArgumentException ex) {
      // Attempt base64
      try {
        return Base64.getDecoder().decode(trimmed);
      } catch (IllegalArgumentException ex2) {
        return null;
      }
    }
  }
}
