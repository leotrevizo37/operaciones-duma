package com.duma.readings.api;

import com.duma.core.config.ModuleProperties;
import com.duma.readings.data.ReadingsRepository;
import com.duma.readings.domain.ReadingsDashboard;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/readings")
public class ReadingsController {
  private final ModuleProperties properties;
  private final ReadingsRepository repository;

  /** JDBC work uses a dedicated executor so blocking queries do not starve the common pool. */
  private final ExecutorService tenantQueries;

  public ReadingsController(ModuleProperties properties, ReadingsRepository repository) {
    this.properties = properties;
    this.repository = repository;
    this.tenantQueries =
        Executors.newFixedThreadPool(Math.max(1, properties.getTenants().size()));
  }

  @PreDestroy
  void close() {
    tenantQueries.shutdown();
  }

  @GetMapping
  public ReadingsDashboard.Response dashboard(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String tenant,
      @RequestParam(defaultValue = "false") boolean diagnostics) {
    LocalDate effectiveTo = to == null ? LocalDate.now() : to;
    LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(30) : from;
    if (effectiveFrom.isAfter(effectiveTo))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "El periodo solicitado no es valido.");
    if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) > 366)
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "El periodo no puede exceder 367 dias.");
    List<CompletableFuture<ReadingsDashboard.TenantResult>> pending =
        selectTenants(tenant).stream()
            .map(
                id ->
                    CompletableFuture.supplyAsync(
                        () -> repository.load(id, effectiveFrom, effectiveTo, diagnostics), tenantQueries))
            .toList();
    List<ReadingsDashboard.TenantResult> results =
        pending.stream().map(CompletableFuture::join).toList();
    return new ReadingsDashboard.Response(Instant.now(), effectiveFrom, effectiveTo, results);
  }

  @GetMapping("/freshness")
  public FreshnessResponse freshness(@RequestParam(required = false) String tenant) {
    return new FreshnessResponse(
        Instant.now(),
        "factRedingsAudits",
        selectTenants(tenant).stream().map(repository::freshness).toList());
  }

  public record FreshnessResponse(
      Instant generatedAt, String ingestionName, List<ReadingsRepository.Freshness> tenants) {}

  private List<String> selectTenants(String tenant) {
    List<String> enabled =
        properties.getTenants().entrySet().stream()
            .filter(entry -> entry.getValue().isEnabled())
            .map(java.util.Map.Entry::getKey)
            .toList();
    if (tenant == null || tenant.isBlank()) return enabled;
    List<String> requested =
        Arrays.stream(tenant.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    if (requested.isEmpty() || requested.stream().anyMatch(value -> !enabled.contains(value)))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Existe un tenant no valido o deshabilitado.");
    return enabled.stream().filter(requested::contains).toList();
  }
}
