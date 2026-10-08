package com.duma.core.security;

import com.duma.core.config.ModuleProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * How a module trusts Duma Web: the key set, the decoder, and the browser-facing headers.
 *
 * <p>These three beans were byte-identical in all four modules, so rotating a key or widening a
 * timeout cost four edits and the divergence between copies was invisible. Every module picks this
 * up through {@code scanBasePackages}; what stays per module is the {@code securityFilterChain},
 * which is where authorization policy actually varies.
 */
@Configuration
public class SharedSecurityConfig {
  /** Refresh before Nimbus's five-minute cache expires. */
  private static final long JWKS_REFRESH_AHEAD_MS = 60_000L;

  private static final int JWKS_CONNECT_TIMEOUT_MS = 2_000;
  private static final int JWKS_READ_TIMEOUT_MS = 5_000;
  private static final int JWKS_SIZE_LIMIT_BYTES = 51_200;

  /** Refresh JWKS asynchronously before expiry, including while the module is idle. */
  @Bean
  JWKSource<SecurityContext> jwkSource(ModuleProperties properties) throws MalformedURLException {
    return JWKSourceBuilder.<SecurityContext>create(
            URI.create(properties.getSecurity().getJwkSetUri()).toURL(),
            // Keep connection and read timeouts explicit for failed JWKS fetches.
            new DefaultResourceRetriever(
                JWKS_CONNECT_TIMEOUT_MS, JWKS_READ_TIMEOUT_MS, JWKS_SIZE_LIMIT_BYTES))
        .refreshAheadCache(JWKS_REFRESH_AHEAD_MS, true)
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder(ModuleProperties properties, JWKSource<SecurityContext> jwkSource) {
    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    // Validators below are the single authority for JWT claims.
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    OAuth2TokenValidator<Jwt> issuer =
        JwtValidators.createDefaultWithIssuer(properties.getSecurity().getIssuer());
    OAuth2TokenValidator<Jwt> audience =
        jwt ->
            jwt.getAudience().contains(properties.getModule().getId())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Audiencia de modulo invalida.", null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
    return decoder;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(ModuleProperties properties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.getSecurity().getAllowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
    configuration.setExposedHeaders(List.of("X-Request-Id"));
    configuration.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
