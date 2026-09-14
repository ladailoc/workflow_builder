package com.fpt.workflow.security.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.PermissionKey;
import com.fpt.workflow.security.RoleKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

  private final byte[] secretKeyBytes;
  private final ObjectMapper objectMapper;

  public JwtTokenService(
      @Value("${platform.security.jwt.secret:}") String configuredSecret,
      ObjectMapper objectMapper) {
    this(configuredSecret, objectMapper, null);
  }

  @Autowired
  public JwtTokenService(
      @Value("${platform.security.jwt.secret:}") String configuredSecret,
      ObjectMapper objectMapper,
      Environment environment) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    if (configuredSecret != null && !configuredSecret.isBlank()) {
      this.secretKeyBytes = configuredSecret.trim().getBytes(StandardCharsets.UTF_8);
    } else {
      boolean protectedProfile =
          environment != null
              && Arrays.stream(environment.getActiveProfiles())
                  .anyMatch(
                      profile ->
                          profile.equalsIgnoreCase("prod")
                              || profile.equalsIgnoreCase("production")
                              || profile.equalsIgnoreCase("staging"));
      if (protectedProfile) {
        throw new IllegalStateException("JWT signing secret is required in staging/production");
      }
      // Safe development/test default key (min 256 bits for HMAC-SHA256)
      this.secretKeyBytes =
          "workflow-platform-default-dev-jwt-secret-key-minimum-256-bits-ok!"
              .getBytes(StandardCharsets.UTF_8);
    }
  }

  public String createToken(
      UUID actorId,
      String actorName,
      Collection<String> roles,
      Collection<String> permissions,
      Duration ttl) {
    Objects.requireNonNull(actorId, "actorId");
    Instant now = Instant.now();
    Instant exp = now.plus(ttl != null ? ttl : Duration.ofHours(24));

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("sub", actorId.toString());
    payload.put("name", actorName != null && !actorName.isBlank() ? actorName.trim() : "Workflow Actor");
    payload.put("iat", now.getEpochSecond());
    payload.put("exp", exp.getEpochSecond());

    ArrayNode rolesArray = payload.putArray("roles");
    if (roles != null) {
      for (String role : roles) {
        if (role != null && !role.isBlank()) {
          rolesArray.add(role.trim().toUpperCase(Locale.ROOT));
        }
      }
    }
    if (rolesArray.isEmpty()) {
      rolesArray.add(RoleKey.USER.value());
    }

    ArrayNode permsArray = payload.putArray("permissions");
    if (permissions != null) {
      for (String perm : permissions) {
        if (perm != null && !perm.isBlank()) {
          permsArray.add(perm.trim().toUpperCase(Locale.ROOT));
        }
      }
    }

    String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
    String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    String contentToSign = headerB64 + "." + payloadB64;
    byte[] signature = sign(contentToSign.getBytes(StandardCharsets.UTF_8));
    String signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

    return contentToSign + "." + signatureB64;
  }

  public Authentication parseAndVerify(String token) {
    if (token == null || token.isBlank()) {
      throw new BadCredentialsException("Token is blank");
    }
    String[] parts = token.trim().split("\\.");
    if (parts.length != 3) {
      throw new BadCredentialsException("Malformed JWT: invalid segment count");
    }

    String headerB64 = parts[0];
    String payloadB64 = parts[1];
    String signatureB64 = parts[2];

    byte[] expectedSignature = sign((headerB64 + "." + payloadB64).getBytes(StandardCharsets.UTF_8));
    byte[] receivedSignature;
    try {
      receivedSignature = Base64.getUrlDecoder().decode(signatureB64);
    } catch (IllegalArgumentException e) {
      throw new BadCredentialsException("Malformed JWT signature encoding", e);
    }

    if (!MessageDigest.isEqual(expectedSignature, receivedSignature)) {
      throw new BadCredentialsException("Invalid or tampered JWT signature");
    }

    try {
      byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
      JsonNode payload = objectMapper.readTree(payloadBytes);

      long exp = payload.path("exp").asLong(0);
      if (exp > 0 && Instant.now().getEpochSecond() > exp) {
        throw new BadCredentialsException("JWT token has expired");
      }

      String sub = payload.path("sub").asText();
      if (sub == null || sub.isBlank()) {
        throw new BadCredentialsException("JWT missing subject (sub)");
      }
      UUID actorId = UUID.fromString(sub);
      String name = payload.path("name").asText("Workflow Actor");

      List<SimpleGrantedAuthority> authorities = new ArrayList<>();
      JsonNode rolesNode = payload.path("roles");
      if (rolesNode.isArray()) {
        for (JsonNode role : rolesNode) {
          try {
            authorities.add(new SimpleGrantedAuthority(RoleKey.of(role.asText()).authority()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      } else if (rolesNode.isTextual() && !rolesNode.asText().isBlank()) {
        for (String role : rolesNode.asText().split(",")) {
          try {
            authorities.add(new SimpleGrantedAuthority(RoleKey.of(role.trim()).authority()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      }

      if (authorities.isEmpty()) {
        authorities.add(new SimpleGrantedAuthority(RoleKey.USER.authority()));
      }

      JsonNode permsNode = payload.path("permissions");
      if (permsNode.isArray()) {
        for (JsonNode perm : permsNode) {
          try {
            authorities.add(new SimpleGrantedAuthority(PermissionKey.of(perm.asText()).authority()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      } else if (permsNode.isTextual() && !permsNode.asText().isBlank()) {
        for (String perm : permsNode.asText().split(",")) {
          try {
            authorities.add(new SimpleGrantedAuthority(PermissionKey.of(perm.trim()).authority()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      }

      AuthenticatedActorPrincipal principal = new AuthenticatedActorPrincipal(actorId, name);
      return UsernamePasswordAuthenticationToken.authenticated(principal, "N/A", authorities);
    } catch (BadCredentialsException e) {
      throw e;
    } catch (Exception e) {
      throw new BadCredentialsException("Malformed JWT claims", e);
    }
  }

  private byte[] sign(byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM));
      return mac.doFinal(data);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to calculate HMAC signature", e);
    }
  }
}
