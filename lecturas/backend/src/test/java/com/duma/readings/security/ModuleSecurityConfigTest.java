package com.duma.readings.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duma.core.config.ModuleProperties;
import com.duma.core.security.SharedSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(
    classes = {ModuleSecurityConfigTest.BehindDumaWebConfig.class, ModuleSecurityConfig.class, SharedSecurityConfig.class})
class ModuleSecurityConfigTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp(WebApplicationContext context) {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void theManifestStaysAnonymous() throws Exception {
    mockMvc.perform(get("/api/module/manifest")).andExpect(status().isOk());
  }

  @Test
  void theHealthProbeStaysAnonymous() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void theModuleReadingStillRejectsAnonymousCallers() throws Exception {
    mockMvc.perform(get("/api/lecturas")).andExpect(status().isUnauthorized());
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  static class BehindDumaWebConfig {
    @Bean
    ModuleProperties moduleProperties() {
      ModuleProperties properties = new ModuleProperties();
      properties.getSecurity().setStandaloneMode(false);
      // Anonymous requests are rejected before JWT decoding, so this URI is never contacted.
      properties.getSecurity().setJwkSetUri("http://localhost:1/api/integration/jwks");
      properties.getSecurity().setIssuer("http://localhost:1");
      return properties;
    }

    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }
  }

  /** Probe endpoints distinguish permitted routes from 404 responses. */
  @RestController
  static class ProbeController {
    @GetMapping("/api/module/manifest")
    String manifest() {
      return "manifest";
    }

    @GetMapping("/actuator/health")
    String health() {
      return "health";
    }

    @GetMapping("/api/lecturas")
    String reading() {
      return "reading";
    }
  }
}
