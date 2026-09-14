package com.fpt.workflow.security.config;

import com.fpt.workflow.security.jwt.JwtAuthenticationFilter;
import com.fpt.workflow.security.jwt.JwtTokenService;
import com.fpt.workflow.security.web.ActorAuthenticationFilter;
import com.fpt.workflow.security.web.ProblemAccessDeniedHandler;
import com.fpt.workflow.security.web.ProblemAuthenticationEntryPoint;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSecurityConfiguration {

  @Value("${platform.security.cors.allowed-origins:*}")
  private String allowedOriginsProperty;

  @Value("${platform.security.dev-headers.enabled:false}")
  private boolean devHeadersProperty;

  @Autowired(required = false)
  private JwtTokenService jwtTokenService;

  @Autowired
  private Environment environment;

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {

    boolean isProdOrStaging =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(
                p ->
                    p.equalsIgnoreCase("prod")
                        || p.equalsIgnoreCase("production")
                        || p.equalsIgnoreCase("staging"));
    boolean allowDevHeaders = devHeadersProperty && !isProdOrStaging;

    if (jwtTokenService != null) {
      http.addFilterBefore(
          new JwtAuthenticationFilter(jwtTokenService), AnonymousAuthenticationFilter.class);
    }
    http.addFilterBefore(
        new ActorAuthenticationFilter(allowDevHeaders), AnonymousAuthenticationFilter.class);

    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/v1/callbacks/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    List<String> origins =
        java.util.Arrays.stream(allowedOriginsProperty.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    if (origins.contains("*") || origins.isEmpty()) {
      configuration.setAllowedOriginPatterns(List.of("*"));
    } else {
      configuration.setAllowedOrigins(origins);
    }
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setExposedHeaders(
        List.of("X-Correlation-Id", "X-Request-Id", "X-Command-Id", "If-Match"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
