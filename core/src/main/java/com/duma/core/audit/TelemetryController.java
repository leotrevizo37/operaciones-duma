package com.duma.core.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {
  private static final Set<String> EVENTS =
      Set.of(
          "MODULE_LOADED",
          "VIEW_RENDERED",
          "FILTER_APPLIED",
          "DRILLDOWN_OPENED",
          "EMPTY_STATE_SHOWN",
          "CLIENT_ERROR");
  private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "CANCELLED");
  private final SystemLogService logs;

  public TelemetryController(SystemLogService logs) {
    this.logs = logs;
  }

  @PostMapping
  public ResponseEntity<Void> record(
      @Valid @RequestBody Request body, Authentication auth, HttpServletRequest request) {
    if (!EVENTS.contains(body.eventName()) || !OUTCOMES.contains(body.outcome()))
      return ResponseEntity.unprocessableEntity().build();
    logs.recordClient(
        body.eventName(),
        body.outcome(),
        auth == null ? "standalone-read" : auth.getName(),
        body.tenantId(),
        body.durationMs(),
        request);
    return ResponseEntity.accepted().build();
  }

  public record Request(
      @NotBlank @Size(max = 80) String eventName,
      @NotBlank @Size(max = 30) String outcome,
      @Size(max = 80) String tenantId,
      @Min(0) @Max(86_400_000) Long durationMs) {}
}
