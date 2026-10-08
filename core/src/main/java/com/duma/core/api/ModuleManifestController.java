package com.duma.core.api;

import com.duma.core.config.ModuleProperties;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/module")
public class ModuleManifestController {
  private final ModuleProperties properties;

  public ModuleManifestController(ModuleProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/manifest")
  public Manifest manifest() {
    ModuleProperties.Module module = properties.getModule();
    return new Manifest(
        "2.0",
        module.getId(),
        module.getDisplayName(),
        module.getApiBaseUrl(),
        module.getReleaseStage(),
        module.getDataEnvironment(),
        module.getFreshnessMode(),
        module.getClearance(),
        module.getTenantScope(),
        module.getCapabilities());
  }

  public record Manifest(
      String protocolVersion,
      String moduleId,
      String displayName,
      String apiBaseUrl,
      String releaseStage,
      String dataEnvironment,
      String freshnessMode,
      String clearance,
      String tenantScope,
      List<String> capabilities) {}
}
