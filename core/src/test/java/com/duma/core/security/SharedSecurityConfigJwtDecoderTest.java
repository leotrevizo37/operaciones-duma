package com.duma.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duma.core.config.ModuleProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSetBasedJWKSource;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSetSourceWrapper;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RefreshAheadCachingJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The token contract of the four modules, asserted once.
 *
 * <p>This test used to exist four times, differing only in the module id it hard-coded. The cases
 * whose outcome depends on that identity are parameterized across the four real ids; the rest are
 * properties of the decoder itself and do not vary by module.
 */
class SharedSecurityConfigJwtDecoderTest {

  private static final String ISSUER = "duma-web-internal";
  private static final String ANY_MODULE = "lecturas";
  private static final String KEY_ID = "test-key";

  private HttpServer server;
  private RSAKey signingKey;
  private final AtomicInteger jwksRequests = new AtomicInteger();

  @BeforeEach
  void startJwksServer() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    String jwks = new JWKSet(signingKey.toPublicJWK()).toString();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks.json",
        exchange -> {
          jwksRequests.incrementAndGet();
          byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stopJwksServer() {
    server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"experiencia-digital", "lecturas", "smartaudits"})
  void acceptsATokenSignedByThePublishedKey(String moduleId) throws Exception {
    assertThat(decoder(moduleId).decode(token(signingKey, ISSUER, moduleId)).getSubject())
        .isEqualTo("duma-web");
  }

  /** Every module against every other one: a token is only ever good for the module it names. */
  @ParameterizedTest
  @CsvSource({
    "experiencia-digital,lecturas",
    "experiencia-digital,smartaudits",
    "lecturas,experiencia-digital",
    "lecturas,smartaudits",
    "smartaudits,experiencia-digital",
    "smartaudits,lecturas"
  })
  void rejectsATokenMintedForAnotherModule(String moduleId, String otherModuleId) throws Exception {
    assertThatThrownBy(() -> decoder(moduleId).decode(token(signingKey, ISSUER, otherModuleId)))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsAnotherIssuer() throws Exception {
    assertThatThrownBy(() -> decoder(ANY_MODULE).decode(token(signingKey, "otro-emisor", ANY_MODULE)))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsATokenSignedByAKeyThatIsNotPublished() throws Exception {
    RSAKey intruder = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();

    // Reuse the key ID so rejection depends on signature verification.
    assertThatThrownBy(() -> decoder(ANY_MODULE).decode(token(intruder, ISSUER, ANY_MODULE)))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void readsTheKeySetOnceForManyTokens() throws Exception {
    JwtDecoder decoder = decoder(ANY_MODULE);
    for (int i = 0; i < 5; i++) {
      decoder.decode(token(signingKey, ISSUER, ANY_MODULE));
    }

    assertThat(jwksRequests.get()).isEqualTo(1);
  }

  @Test
  void renewsTheKeySetAheadOfExpiry() throws Exception {
    ModuleProperties properties = new ModuleProperties();
    properties.getSecurity().setJwkSetUri("http://127.0.0.1:1/jwks.json");

    // Inspect composition instead of waiting for the five-minute cache to expire.
    JWKSetSource<SecurityContext> chain =
        ((JWKSetBasedJWKSource<SecurityContext>) new SharedSecurityConfig().jwkSource(properties))
            .getJWKSetSource();

    RefreshAheadCachingJWKSetSource<SecurityContext> refreshAhead = null;
    while (chain != null) {
      if (chain instanceof RefreshAheadCachingJWKSetSource<SecurityContext> found) {
        refreshAhead = found;
        break;
      }
      chain =
          chain instanceof JWKSetSourceWrapper<SecurityContext> wrapper ? wrapper.getSource() : null;
    }

    assertThat(refreshAhead).isNotNull();
    assertThat(refreshAhead.getScheduledExecutorService()).isNotNull();
  }

  private JwtDecoder decoder(String moduleId) throws IOException {
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId(moduleId);
    properties.getSecurity().setIssuer(ISSUER);
    properties
        .getSecurity()
        .setJwkSetUri("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json");

    SharedSecurityConfig config = new SharedSecurityConfig();
    JWKSource<SecurityContext> source = config.jwkSource(properties);
    return config.jwtDecoder(properties, source);
  }

  private String token(RSAKey key, String issuer, String audience) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("duma-web")
            .issuer(issuer)
            .audience(List.of(audience))
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(120)))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
