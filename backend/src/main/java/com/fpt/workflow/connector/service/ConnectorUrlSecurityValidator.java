package com.fpt.workflow.connector.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates outbound connector URLs to prevent Server-Side Request Forgery (SSRF) attacks targeting
 * internal networks, localhost, or cloud instance metadata services.
 */
@Component
public class ConnectorUrlSecurityValidator {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  private static final Set<String> BLOCKED_HOSTS =
      Set.of(
          "localhost",
          "127.0.0.1",
          "::1",
          "0.0.0.0",
          "169.254.169.254",
          "metadata.google.internal",
          "instance-data");

  /**
   * Validates that the provided URL is safe for outbound connector requests. Throws
   * SecurityException if the URL points to a forbidden destination.
   */
  public void validateUrl(String urlString) {
    if (urlString == null || urlString.isBlank()) {
      throw new IllegalArgumentException("Connector URL must not be blank");
    }

    URI uri;
    try {
      uri = URI.create(urlString.trim());
    } catch (IllegalArgumentException ex) {
      throw new SecurityException("Invalid connector URI: " + urlString, ex);
    }

    String scheme = uri.getScheme();
    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new SecurityException("Disallowed connector protocol scheme: " + scheme);
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SecurityException("Connector URL host is missing: " + urlString);
    }

    String lowerHost = host.toLowerCase(Locale.ROOT);
    if (BLOCKED_HOSTS.contains(lowerHost) || lowerHost.endsWith(".localhost")) {
      throw new SecurityException("SSRF blocked: Destination host is forbidden (" + host + ")");
    }

    // Check IP address ranges if host resolves to an IP address
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      for (InetAddress addr : addresses) {
        if (addr.isLoopbackAddress()
            || addr.isAnyLocalAddress()
            || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress()
            || addr.isMulticastAddress()) {
          throw new SecurityException(
              "SSRF blocked: Destination resolves to private/internal network ("
                  + addr.getHostAddress()
                  + ")");
        }
        String ipStr = addr.getHostAddress();
        if (ipStr.startsWith("169.254.") || ipStr.equals("127.0.0.1") || ipStr.equals("0.0.0.0")) {
          throw new SecurityException(
              "SSRF blocked: Cloud metadata or loopback IP (" + ipStr + ")");
        }
      }
    } catch (UnknownHostException e) {
      // If host cannot be resolved during configuration validation, check string format
      if (lowerHost.startsWith("169.254.")
          || lowerHost.startsWith("127.")
          || lowerHost.startsWith("10.")) {
        throw new SecurityException("SSRF blocked: Private/metadata IP format (" + host + ")");
      }
    }
  }

  public boolean isAllowed(String urlString) {
    try {
      validateUrl(urlString);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
