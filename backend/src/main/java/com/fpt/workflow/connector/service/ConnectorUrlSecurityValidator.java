package com.fpt.workflow.connector.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates outbound connector URLs to prevent Server-Side Request Forgery (SSRF) attacks targeting
 * internal networks, localhost, or cloud instance metadata services (§17.2 / §29.1).
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
   * Validates that the provided URL is safe for outbound connector requests.
   *
   * @throws SecurityException if the URL points to a forbidden destination
   * @throws IllegalArgumentException if the URL is blank
   */
  public void validateUrl(String urlString) {
    validate(urlString, null, null, null);
  }

  /**
   * Validates that the provided URL is safe and adheres to configured host allowlist.
   */
  public void validateUrl(String urlString, Set<String> allowedHosts) {
    validate(urlString, allowedHosts, null, null);
  }

  /**
   * Validates URL, host allowlist, and HTTP method allowlist.
   */
  public void validate(
      String urlString,
      Set<String> allowedHosts,
      String httpMethod,
      Set<String> allowedMethods) {
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

    // Validate against host allowlist if configured
    if (allowedHosts != null && !allowedHosts.isEmpty()) {
      boolean hostMatched = false;
      for (String allowed : allowedHosts) {
        if (allowed == null || allowed.isBlank()) continue;
        String lowerAllowed = allowed.trim().toLowerCase(Locale.ROOT);
        if (lowerAllowed.startsWith("*.")) {
          String suffix = lowerAllowed.substring(1); // e.g. ".example.com"
          if (lowerHost.endsWith(suffix) || lowerHost.equals(lowerAllowed.substring(2))) {
            hostMatched = true;
            break;
          }
        } else if (lowerHost.equals(lowerAllowed)) {
          hostMatched = true;
          break;
        }
      }
      if (!hostMatched) {
        throw new SecurityException("SSRF blocked: Host " + host + " is not in allowedHosts");
      }
    }

    // Validate HTTP method if configured
    if (httpMethod != null && allowedMethods != null && !allowedMethods.isEmpty()) {
      String upperMethod = httpMethod.trim().toUpperCase(Locale.ROOT);
      boolean methodAllowed = false;
      for (String m : allowedMethods) {
        if (m != null && m.trim().equalsIgnoreCase(upperMethod)) {
          methodAllowed = true;
          break;
        }
      }
      if (!methodAllowed) {
        throw new SecurityException("SSRF blocked: HTTP method " + httpMethod + " is not allowed");
      }
    }

    // Validate IP string formats before DNS resolution
    validateIpStringFormat(lowerHost);

    // Check IP address ranges for all resolved addresses (DNS rebinding defense)
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      for (InetAddress addr : addresses) {
        validateInetAddress(addr);
      }
    } catch (UnknownHostException e) {
      // Host resolution failed - ensure IP string format was already checked
      validateIpStringFormat(lowerHost);
    }
  }

  public boolean isAllowed(String urlString) {
    return isAllowed(urlString, null);
  }

  public boolean isAllowed(String urlString, Set<String> allowedHosts) {
    try {
      validateUrl(urlString, allowedHosts);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void validateInetAddress(InetAddress addr) {
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

    byte[] bytes = addr.getAddress();

    if (bytes.length == 4) {
      validateIpv4Bytes(bytes, addr.getHostAddress());
    } else if (bytes.length == 16) {
      validateIpv6Bytes(bytes, addr);
    }
  }

  private void validateIpv4Bytes(byte[] bytes, String ipStr) {
    int b0 = bytes[0] & 0xff;
    int b1 = bytes[1] & 0xff;

    // 0.0.0.0/8 (Current network)
    if (b0 == 0) {
      throw new SecurityException("SSRF blocked: Zero address network (" + ipStr + ")");
    }
    // 10.0.0.0/8 (RFC1918)
    if (b0 == 10) {
      throw new SecurityException("SSRF blocked: Private RFC1918 IP (" + ipStr + ")");
    }
    // 127.0.0.0/8 (Loopback)
    if (b0 == 127) {
      throw new SecurityException("SSRF blocked: Loopback IP (" + ipStr + ")");
    }
    // 100.64.0.0/10 (Shared Address Space / CGNAT)
    if (b0 == 100 && (b1 & 0xc0) == 64) {
      throw new SecurityException("SSRF blocked: CGNAT IP (" + ipStr + ")");
    }
    // 169.254.0.0/16 (Link-local / Cloud metadata)
    if (b0 == 169 && b1 == 254) {
      throw new SecurityException("SSRF blocked: Cloud metadata or link-local IP (" + ipStr + ")");
    }
    // 172.16.0.0/12 (RFC1918: 172.16.0.0 - 172.31.255.255)
    if (b0 == 172 && (b1 & 0xf0) == 16) {
      throw new SecurityException("SSRF blocked: Private RFC1918 IP (" + ipStr + ")");
    }
    // 192.168.0.0/16 (RFC1918)
    if (b0 == 192 && b1 == 168) {
      throw new SecurityException("SSRF blocked: Private RFC1918 IP (" + ipStr + ")");
    }
    // 224.0.0.0/4 (Multicast) or 240.0.0.0/4 (Reserved)
    if (b0 >= 224) {
      throw new SecurityException("SSRF blocked: Multicast or reserved IP (" + ipStr + ")");
    }
  }

  private void validateIpv6Bytes(byte[] bytes, InetAddress addr) {
    int b0 = bytes[0] & 0xff;
    int b1 = bytes[1] & 0xff;

    // ::1 (Loopback)
    boolean allZeroExceptLast = true;
    for (int i = 0; i < 15; i++) {
      if (bytes[i] != 0) {
        allZeroExceptLast = false;
        break;
      }
    }
    if (allZeroExceptLast && bytes[15] == 1) {
      throw new SecurityException("SSRF blocked: IPv6 loopback (::1)");
    }

    // :: (Unspecified)
    boolean allZero = allZeroExceptLast && bytes[15] == 0;
    if (allZero) {
      throw new SecurityException("SSRF blocked: IPv6 unspecified (::)");
    }

    // IPv6 Unique Local Address (ULA) fc00::/7 (fc00::/8 and fd00::/8)
    if ((b0 & 0xfe) == 0xfc) {
      throw new SecurityException(
          "SSRF blocked: IPv6 Unique Local Address (" + addr.getHostAddress() + ")");
    }

    // IPv6 Link-Local fe80::/10
    if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
      throw new SecurityException(
          "SSRF blocked: IPv6 link-local (" + addr.getHostAddress() + ")");
    }

    // IPv4-mapped IPv6 address (::ffff:x.x.x.x)
    boolean isMapped = true;
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        isMapped = false;
        break;
      }
    }
    if (isMapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
      byte[] ipv4Bytes = new byte[4];
      System.arraycopy(bytes, 12, ipv4Bytes, 0, 4);
      validateIpv4Bytes(ipv4Bytes, addr.getHostAddress());
    }
  }

  private void validateIpStringFormat(String lowerHost) {
    // Strip IPv6 brackets if present
    String cleanHost = lowerHost;
    if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
      cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
    }

    if (cleanHost.equals("::1") || cleanHost.equals("0:0:0:0:0:0:0:1")) {
      throw new SecurityException("SSRF blocked: IPv6 loopback");
    }
    if (cleanHost.startsWith("fc") || cleanHost.startsWith("fd")) {
      throw new SecurityException("SSRF blocked: IPv6 ULA format (" + lowerHost + ")");
    }
    if (cleanHost.startsWith("fe80:") || cleanHost.startsWith("fe80::")) {
      throw new SecurityException("SSRF blocked: IPv6 link-local format (" + lowerHost + ")");
    }
    if (cleanHost.startsWith("::ffff:")) {
      String mappedIpv4 = cleanHost.substring("::ffff:".length());
      validateIpStringFormat(mappedIpv4);
    }

    // IPv4 prefix checks
    if (cleanHost.startsWith("169.254.")
        || cleanHost.startsWith("127.")
        || cleanHost.startsWith("10.")
        || cleanHost.startsWith("192.168.")
        || cleanHost.startsWith("0.")) {
      throw new SecurityException("SSRF blocked: Private/metadata IP format (" + lowerHost + ")");
    }
    if (cleanHost.startsWith("172.")) {
      String[] parts = cleanHost.split("\\.");
      if (parts.length >= 2) {
        try {
          int second = Integer.parseInt(parts[1]);
          if (second >= 16 && second <= 31) {
            throw new SecurityException("SSRF blocked: RFC1918 172.16-31 IP format (" + lowerHost + ")");
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }
    if (cleanHost.startsWith("100.")) {
      String[] parts = cleanHost.split("\\.");
      if (parts.length >= 2) {
        try {
          int second = Integer.parseInt(parts[1]);
          if (second >= 64 && second <= 127) {
            throw new SecurityException("SSRF blocked: CGNAT 100.64-127 IP format (" + lowerHost + ")");
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }
  }
}
