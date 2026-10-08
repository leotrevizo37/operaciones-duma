package com.duma.experience.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.config.ModuleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class PortConfigurationTest {

  @Test
  void resolvesTheCanonicalBackendPortIntoServerUrl() throws Exception {
    String modulePort = System.getProperty("DUMA_EXPERIENCE_BACKEND_PORT");
    try {
      System.setProperty("DUMA_EXPERIENCE_BACKEND_PORT", "19081");
      StandardEnvironment environment = environment();

      assertThat(environment.getProperty("server.port")).isEqualTo("19081");
      assertThat(environment.getProperty("duma.module.api-base-url"))
          .isEqualTo("http://localhost:19081");
    } finally {
      restore("DUMA_EXPERIENCE_BACKEND_PORT", modulePort);
    }
  }

  @Test
  void allowsNoBrowserOriginUntilOneIsDeclared() throws Exception {
    String declared = System.getProperty("DUMA_EXPERIENCE_ALLOWED_ORIGINS");
    try {
      System.clearProperty("DUMA_EXPERIENCE_ALLOWED_ORIGINS");
      assertThat(environment().getProperty("duma.security.allowed-origins")).isEmpty();

      System.setProperty("DUMA_EXPERIENCE_ALLOWED_ORIGINS", "https://duma.example");
      assertThat(environment().getProperty("duma.security.allowed-origins"))
          .isEqualTo("https://duma.example");
    } finally {
      restore("DUMA_EXPERIENCE_ALLOWED_ORIGINS", declared);
    }
  }

  @Test
  void readsIssuerAndJwksOnlyFromTheirOwnVariables() throws Exception {
    String issuer = System.getProperty("DUMA_AUTH_ISSUER");
    String jwks = System.getProperty("DUMA_AUTH_JWKS_URI");
    try {
      System.setProperty("DUMA_AUTH_ISSUER", "duma-web-internal");
      System.setProperty("DUMA_AUTH_JWKS_URI", "http://jwks.example.invalid/.well-known/jwks.json");
      StandardEnvironment environment = environment();

      assertThat(environment.getProperty("duma.security.issuer")).isEqualTo("duma-web-internal");
      assertThat(environment.getProperty("duma.security.jwk-set-uri"))
          .isEqualTo("http://jwks.example.invalid/.well-known/jwks.json");
    } finally {
      restore("DUMA_AUTH_ISSUER", issuer);
      restore("DUMA_AUTH_JWKS_URI", jwks);
    }
  }

  @Test
  void leavesIssuerAndJwksEmptyWhenNobodyDeclaresThem() throws Exception {
    String issuer = System.getProperty("DUMA_AUTH_ISSUER");
    String jwks = System.getProperty("DUMA_AUTH_JWKS_URI");
    try {
      System.clearProperty("DUMA_AUTH_ISSUER");
      System.clearProperty("DUMA_AUTH_JWKS_URI");
      StandardEnvironment environment = environment();

      assertThat(environment.getProperty("duma.security.issuer")).isEmpty();
      assertThat(environment.getProperty("duma.security.jwk-set-uri")).isEmpty();
    } finally {
      restore("DUMA_AUTH_ISSUER", issuer);
      restore("DUMA_AUTH_JWKS_URI", jwks);
    }
  }

  @Test
  void securityCarriesNoFallbackWrittenInJava() {
    ModuleProperties.Security security = new ModuleProperties().getSecurity();

    assertThat(security.getIssuer()).isNull();
    assertThat(security.getJwkSetUri()).isNull();
  }

  private StandardEnvironment environment() throws Exception {
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"))
        .forEach(environment.getPropertySources()::addLast);
    return environment;
  }

  private void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }
}
