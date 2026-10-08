package com.duma.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.config.ModuleProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The browser-facing surface of all four modules.
 *
 * <p>Until this bean moved to core, widening it by mistake broke one module. It now serves the
 * four, so the same slip reaches all of them at once — and a single test guards all of them.
 */
class SharedSecurityConfigCorsTest {

  private static final String ORIGIN = "https://duma.example";

  @Test
  void servesOnlyTheOriginsTheModuleDeclares() {
    assertThat(cors(ORIGIN).getAllowedOrigins()).containsExactly(ORIGIN);
  }

  @Test
  void keepsTheVerbsAndHeadersNarrow() {
    CorsConfiguration configuration = cors(ORIGIN);

    assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "OPTIONS");
    assertThat(configuration.getAllowedHeaders())
        .containsExactly("Authorization", "Content-Type", "X-Request-Id");
    assertThat(configuration.getExposedHeaders()).containsExactly("X-Request-Id");
  }

  /**
   * Duma Web carries the token in an Authorization header, never in a cookie the browser would
   * attach on its own. Allowing credentials would let any page the browser trusts spend the user's
   * ambient session against a module.
   */
  @Test
  void neverAllowsCredentials() {
    assertThat(cors(ORIGIN).getAllowCredentials()).isFalse();
  }

  private CorsConfiguration cors(String... origins) {
    ModuleProperties properties = new ModuleProperties();
    properties.getSecurity().setAllowedOrigins(List.of(origins));

    UrlBasedCorsConfigurationSource source =
        (UrlBasedCorsConfigurationSource)
            new SharedSecurityConfig().corsConfigurationSource(properties);

    return source.getCorsConfigurations().get("/**");
  }
}
