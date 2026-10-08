package com.duma.smartaudits.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duma.core.config.ModuleProperties;
import com.duma.core.security.SharedSecurityConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

// Isolate nested test configuration from component scanning in other Spring tests.
@ExtendWith(SpringExtension.class)
class ModuleSecurityConfigTest {

  private static final String MODULE_READING = "/api/smartaudits";

  private static final String QUEUE = MODULE_READING + "/review-queue";

  private static final String APPROVE = QUEUE + "/approve";

  private static final SimpleGrantedAuthority WRITE =
      new SimpleGrantedAuthority("SCOPE_observability:write");

  private static final SimpleGrantedAuthority READ =
      new SimpleGrantedAuthority("SCOPE_observability:read");

  private static final String BODY = "{\"normalizedCommentHash\":\"x\"}";

  private static MockHttpServletRequestBuilder withoutScope(MockHttpServletRequestBuilder request) {
    return request.with(jwt().authorities(List.of()));
  }

  private static MockHttpServletRequestBuilder withReadOnly(MockHttpServletRequestBuilder request) {
    return request.with(jwt().authorities(READ));
  }

  private static MockHttpServletRequestBuilder withWrite(MockHttpServletRequestBuilder request) {
    return request.with(jwt().authorities(READ, WRITE));
  }

  private static MockHttpServletRequestBuilder approval() {
    return post(APPROVE).contentType(MediaType.APPLICATION_JSON).content(BODY);
  }

  abstract static class SecurityContract {
    MockMvc mockMvc;

    @BeforeEach
    void setUp(WebApplicationContext context) {
      mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void approvalWithoutTheWriteScopeIsForbidden() throws Exception {
      mockMvc.perform(withoutScope(approval())).andExpect(status().isForbidden());
    }

    @Test
    void approvalWithOnlyTheReadScopeIsForbidden() throws Exception {
      mockMvc.perform(withReadOnly(approval())).andExpect(status().isForbidden());
    }

    @Test
    void approvalWithTheWriteScopePasses() throws Exception {
      mockMvc.perform(withWrite(approval())).andExpect(status().isOk());
    }

    @Test
    void readingTheQueueWithoutTheWriteScopeStillPasses() throws Exception {
      mockMvc.perform(withoutScope(get(QUEUE))).andExpect(status().isOk());
      mockMvc.perform(withReadOnly(get(QUEUE))).andExpect(status().isOk());
    }

    @Test
    void anonymousCallersAreStillRejectedOnBothVerbs() throws Exception {
      mockMvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
      mockMvc.perform(approval()).andExpect(status().isUnauthorized());
    }

    @Test
    void theManifestStaysAnonymous() throws Exception {
      mockMvc.perform(get("/api/module/manifest")).andExpect(status().isOk());
    }

    @Test
    void theHealthProbeStaysAnonymous() throws Exception {
      mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
  }

  @Nested
  @ExtendWith(SpringExtension.class)
  @WebAppConfiguration
  @ContextConfiguration(classes = {BehindTheShellConfig.class, ModuleSecurityConfig.class, SharedSecurityConfig.class})
  class BehindTheShell extends SecurityContract {
    @Test
    void theModuleReadingStillRejectsAnonymousCallers() throws Exception {
      mockMvc.perform(get(MODULE_READING)).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @ExtendWith(SpringExtension.class)
  @WebAppConfiguration
  @ContextConfiguration(classes = {StandaloneConfig.class, ModuleSecurityConfig.class, SharedSecurityConfig.class})
  class Standalone extends SecurityContract {}

  @TestConfiguration
  @EnableWebMvc
  @EnableWebSecurity
  static class BehindTheShellConfig {
    @Bean
    ModuleProperties moduleProperties() {
      return properties(false);
    }

    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }

    @Bean
    ActuatorProbeController actuatorProbeController() {
      return new ActuatorProbeController();
    }
  }

  @TestConfiguration
  @EnableWebMvc
  @EnableWebSecurity
  static class StandaloneConfig {
    @Bean
    ModuleProperties moduleProperties() {
      return properties(true);
    }

    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }

    @Bean
    ActuatorProbeController actuatorProbeController() {
      return new ActuatorProbeController();
    }
  }

  private static ModuleProperties properties(boolean standalone) {
    ModuleProperties properties = new ModuleProperties();
    properties.getSecurity().setStandaloneMode(standalone);
    properties.getSecurity().setJwkSetUri("http://localhost:1/api/integration/jwks");
    properties.getSecurity().setIssuer("http://localhost:1");
    return properties;
  }

  @RestController
  @RequestMapping("/api")
  static class ProbeController {
    @GetMapping("/smartaudits")
    String reading() {
      return "reading";
    }

    @GetMapping("/smartaudits/review-queue")
    String pending() {
      return "queue";
    }

    @PostMapping("/smartaudits/review-queue/approve")
    String approve() {
      return "approved";
    }

    @GetMapping("/module/manifest")
    String manifest() {
      return "manifest";
    }
  }

  @RestController
  static class ActuatorProbeController {
    @GetMapping("/actuator/health")
    String health() {
      return "health";
    }
  }
}
