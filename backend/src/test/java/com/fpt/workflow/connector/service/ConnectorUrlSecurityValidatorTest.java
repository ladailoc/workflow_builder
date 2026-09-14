package com.fpt.workflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorUrlSecurityValidatorTest {

  private ConnectorUrlSecurityValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ConnectorUrlSecurityValidator();
  }

  @Test
  @DisplayName("1. Localhost destinations are blocked")
  void testLocalhostBlocked() {
    assertThat(validator.isAllowed("http://localhost:8080/api")).isFalse();
    assertThat(validator.isAllowed("https://sub.localhost:8080")).isFalse();
    assertThat(validator.isAllowed("http://localHost/test")).isFalse();

    assertThatThrownBy(() -> validator.validateUrl("http://localhost/admin"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("SSRF blocked");
  }

  @Test
  @DisplayName("2. 127.0.0.1 and loopback IPv4 addresses are blocked")
  void testLoopbackIpv4Blocked() {
    assertThat(validator.isAllowed("http://127.0.0.1:8080")).isFalse();
    assertThat(validator.isAllowed("http://127.1.2.3:9000")).isFalse();
    assertThat(validator.isAllowed("http://0.0.0.0:80")).isFalse();

    assertThatThrownBy(() -> validator.validateUrl("http://127.0.0.1:3000"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("SSRF blocked");
  }

  @Test
  @DisplayName("3. Private RFC1918 ranges are blocked (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)")
  void testRfc1918PrivateRangesBlocked() {
    // 10.0.0.0/8
    assertThat(validator.isAllowed("http://10.0.0.1/api")).isFalse();
    assertThat(validator.isAllowed("http://10.255.255.254/internal")).isFalse();

    // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
    assertThat(validator.isAllowed("http://172.16.0.1/status")).isFalse();
    assertThat(validator.isAllowed("http://172.24.5.10/admin")).isFalse();
    assertThat(validator.isAllowed("http://172.31.255.255/db")).isFalse();

    // 192.168.0.0/16
    assertThat(validator.isAllowed("http://192.168.1.1/router")).isFalse();
    assertThat(validator.isAllowed("http://192.168.100.50:8080/data")).isFalse();

    // Cloud metadata & link-local (169.254.0.0/16)
    assertThat(validator.isAllowed("http://169.254.169.254/latest/meta-data")).isFalse();

    // CGNAT (100.64.0.0/10)
    assertThat(validator.isAllowed("http://100.64.1.1/cgnat")).isFalse();
    assertThat(validator.isAllowed("http://100.127.255.255/cgnat")).isFalse();
  }

  @Test
  @DisplayName("4. IPv6 loopback, ULA, link-local, and IPv4-mapped forms are blocked")
  void testIpv6PrivateAndLoopbackBlocked() {
    // ::1 loopback
    assertThat(validator.isAllowed("http://[::1]:8080")).isFalse();

    // Unique Local Address (fc00::/7, including fd00::/8)
    assertThat(validator.isAllowed("http://[fc00::1]:8080")).isFalse();
    assertThat(validator.isAllowed("http://[fd12:3456:789a::1]:8080")).isFalse();

    // Link-local (fe80::/10)
    assertThat(validator.isAllowed("http://[fe80::1]:8080")).isFalse();

    // IPv4-mapped IPv6 (::ffff:127.0.0.1, ::ffff:10.0.0.1)
    assertThat(validator.isAllowed("http://[::ffff:127.0.0.1]:8080")).isFalse();
    assertThat(validator.isAllowed("http://[::ffff:10.0.0.1]:8080")).isFalse();
  }

  @Test
  @DisplayName("5. Disallowed protocol schemes are blocked")
  void testDisallowedSchemesBlocked() {
    assertThat(validator.isAllowed("file:///etc/passwd")).isFalse();
    assertThat(validator.isAllowed("gopher://127.0.0.1:70")).isFalse();
    assertThat(validator.isAllowed("ftp://ftp.example.com/file")).isFalse();
    assertThat(validator.isAllowed("ldap://10.0.0.1:389")).isFalse();
    assertThat(validator.isAllowed("javascript:alert(1)")).isFalse();

    assertThatThrownBy(() -> validator.validateUrl("file:///etc/passwd"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("Disallowed connector protocol scheme");
  }

  @Test
  @DisplayName("6. Allowed public URLs succeed")
  void testAllowedPublicUrls() {
    assertThat(validator.isAllowed("https://api.github.com/repos")).isTrue();
    assertThat(validator.isAllowed("http://example.com/webhook")).isTrue();
    assertThat(validator.isAllowed("https://httpbin.org/post")).isTrue();

    assertThatCode(() -> validator.validateUrl("https://api.github.com/repos"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("7. Configured host restrictions are strictly enforced")
  void testConfiguredHostRestrictions() {
    Set<String> allowedHosts = Set.of("api.service.com", "*.partner.org");

    // Exact match allowed
    assertThat(validator.isAllowed("https://api.service.com/v1/orders", allowedHosts)).isTrue();

    // Wildcard match allowed
    assertThat(validator.isAllowed("https://sub.partner.org/data", allowedHosts)).isTrue();
    assertThat(validator.isAllowed("https://partner.org/data", allowedHosts)).isTrue();

    // Non-allowed hosts blocked even if public
    assertThat(validator.isAllowed("https://api.github.com/repos", allowedHosts)).isFalse();
    assertThat(validator.isAllowed("https://evil.com/leak", allowedHosts)).isFalse();

    assertThatThrownBy(
            () ->
                validator.validateUrl("https://api.github.com/repos", allowedHosts))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("not in allowedHosts");
  }

  @Test
  @DisplayName("8. Configured HTTP method restrictions are enforced")
  void testConfiguredMethodRestrictions() {
    Set<String> allowedMethods = Set.of("GET", "POST");

    assertThatCode(
            () ->
                validator.validate(
                    "https://api.github.com/repos", null, "GET", allowedMethods))
        .doesNotThrowAnyException();

    assertThatCode(
            () ->
                validator.validate(
                    "https://api.github.com/repos", null, "post", allowedMethods))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                validator.validate(
                    "https://api.github.com/repos", null, "DELETE", allowedMethods))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("HTTP method DELETE is not allowed");
  }
}
