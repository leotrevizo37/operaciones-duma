package com.duma.smartaudits.api;

import com.duma.core.config.ModuleProperties;
import com.duma.smartaudits.data.SmartAuditsRepository;
import com.duma.smartaudits.domain.SmartAuditsDashboard;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/smartaudits")
public class SmartAuditsController {
  private final ModuleProperties properties;
  private final SmartAuditsRepository repository;

  public SmartAuditsController(ModuleProperties properties, SmartAuditsRepository repository) {
    this.properties = properties;
    this.repository = repository;
  }

  @GetMapping
  public SmartAuditsDashboard.Response dashboard(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String tenant) {
    LocalDate effectiveTo = to == null ? LocalDate.now() : to;
    LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(30) : from;
    if (effectiveFrom.isAfter(effectiveTo)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "El periodo solicitado no es valido.");
    }
    if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) > 366) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "El periodo no puede exceder 367 dias.");
    }
    List<SmartAuditsDashboard.TenantResult> tenants =
        select(tenant).stream().map(id -> repository.load(id, effectiveFrom, effectiveTo)).toList();
    return new SmartAuditsDashboard.Response(Instant.now(), effectiveFrom, effectiveTo, tenants);
  }

  @GetMapping("/freshness")
  public FreshnessResponse freshness(@RequestParam(required = false) String tenant) {
    return new FreshnessResponse(
        Instant.now(),
        "factSmartauditsCategories",
        select(tenant).stream().map(repository::freshness).toList());
  }

  public record FreshnessResponse(
      Instant generatedAt, String ingestionName, List<SmartAuditsRepository.Freshness> tenants) {}

  private List<String> select(String tenant) {
    List<String> enabled =
        properties.getTenants().entrySet().stream()
            .filter(entry -> entry.getValue().isEnabled())
            .map(java.util.Map.Entry::getKey)
            .toList();
    if (tenant == null || tenant.isBlank()) {
      return enabled;
    }
    List<String> requested =
        Arrays.stream(tenant.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    if (requested.isEmpty() || requested.stream().anyMatch(value -> !enabled.contains(value))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Existe un tenant no valido o deshabilitado.");
    }
    return enabled.stream().filter(requested::contains).toList();
  }
}
