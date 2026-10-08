package com.duma.readings;

import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.api.ModuleManifestController;
import com.duma.core.audit.RequestIdFilter;
import com.duma.core.audit.SystemLogService;
import com.duma.core.audit.TelemetryController;
import com.nimbusds.jose.jwk.source.JWKSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(
    properties = {
      "duma.security.standalone-mode=true",
      "duma.security.issuer=duma-web-internal",
      "duma.security.jwk-set-uri=http://127.0.0.1:1/jwks.json"
    })
class ModuleWiringTest {
  @Autowired ApplicationContext context;

  @Test
  void registersTheSharedFrameworkBeans() {
    assertThat(context.getBeansOfType(SystemLogService.class)).hasSize(1);
    assertThat(context.getBeansOfType(RequestIdFilter.class)).hasSize(1);
    assertThat(context.getBeansOfType(TelemetryController.class)).hasSize(1);
    assertThat(context.getBeansOfType(ModuleManifestController.class)).hasSize(1);
  }

  @Test
  void registersTheSharedSecurityBeansExactlyOnce() {
    assertThat(context.getBeansOfType(JWKSource.class)).hasSize(1);
    assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
    // Not by type: Spring MVC's own mvcHandlerMappingIntrospector is a second implementation.
    // The bean name is what the four modules shared and what core now owns.
    assertThat(context.getBean("corsConfigurationSource", CorsConfigurationSource.class))
        .isNotNull();
  }

  @Test
  void publishesTheManifestRouteFromTheSharedController() {
    // Actuator also exposes this type.
    RequestMappingHandlerMapping handlerMapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

    assertThat(
            handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(ModuleManifestController.class))
                .flatMap(entry -> entry.getKey().getDirectPaths().stream()))
        .contains("/api/module/manifest");
  }
}
