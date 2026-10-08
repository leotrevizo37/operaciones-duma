package com.duma.smartaudits.security;

import com.duma.core.config.ModuleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ModuleSecurityConfig {
  /** Required only to approve review-queue items; reads remain authenticated-only. */
  private static final String WRITE_SCOPE_AUTHORITY = "SCOPE_observability:write";

  private static final String REVIEW_QUEUE_PATHS = "/api/smartaudits/review-queue/**";

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ModuleProperties properties)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (properties.getSecurity().isStandaloneMode()) {
      http.authorizeHttpRequests(
          auth ->
              auth.requestMatchers("/api/module/manifest", "/actuator/health")
                  .permitAll()
                  .requestMatchers(HttpMethod.GET, "/api/smartaudits")
                  .permitAll()
                  .requestMatchers(HttpMethod.POST, "/api/telemetry")
                  .permitAll()
                  // Spring uses the first matching rule; keep the scoped POST before broader paths.
                  .requestMatchers(HttpMethod.POST, REVIEW_QUEUE_PATHS)
                  .hasAuthority(WRITE_SCOPE_AUTHORITY)
                  .requestMatchers(REVIEW_QUEUE_PATHS)
                  .authenticated()
                  .anyRequest()
                  .permitAll());
    } else {
      http.authorizeHttpRequests(
          auth ->
              auth.requestMatchers("/api/module/manifest", "/actuator/health")
                  .permitAll()
                  // Keep the scoped POST before the authenticated API catch-all.
                  .requestMatchers(HttpMethod.POST, REVIEW_QUEUE_PATHS)
                  .hasAuthority(WRITE_SCOPE_AUTHORITY)
                  .requestMatchers("/api/**")
                  .authenticated()
                  .anyRequest()
                  .permitAll());
    }
    http.oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
