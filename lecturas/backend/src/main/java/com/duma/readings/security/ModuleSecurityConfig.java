package com.duma.readings.security;

import com.duma.core.config.ModuleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ModuleSecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ModuleProperties properties)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (properties.getSecurity().isStandaloneMode())
      http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    else
      http.authorizeHttpRequests(
              authorize ->
                  authorize
                      .requestMatchers("/api/module/manifest", "/actuator/health")
                      .permitAll()
                      .requestMatchers("/api/**")
                      .authenticated()
                      .anyRequest()
                      .permitAll())
          .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
