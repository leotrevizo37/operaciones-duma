package com.duma.core.audit;

import com.duma.core.config.ModuleProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class SystemLogService {
  private static final Logger log = LoggerFactory.getLogger(SystemLogService.class);
  // This shared service must use the configured module ID to keep audit trails attributable.
  private final String applicationId;
  private final JdbcTemplate jdbc;

  public SystemLogService(JdbcTemplate jdbc, ModuleProperties properties) {
    this.jdbc = jdbc;
    this.applicationId = properties.getModule().getId();
  }

  // Stays void even though record() now reports: Spring's async interceptor throws
  // IllegalArgumentException for any @Async return type other than void, Future or
  // CompletableFuture, and the only caller runs after the response is already committed.
  @Async
  public void recordRequest(
      String method,
      String uri,
      int status,
      long duration,
      String requestId,
      String actor,
      String sourceIp,
      String userAgent) {
    record(
        "HTTP_REQUEST",
        method + " " + cut(uri, 300),
        status < 400 ? "SUCCESS" : "FAILURE",
        status >= 500 ? "ERROR" : status >= 400 ? "WARNING" : "INFO",
        requestId,
        actor,
        null,
        duration,
        sourceIp,
        userAgent);
  }

  public boolean recordClient(
      String name,
      String outcome,
      String actor,
      String tenant,
      Long duration,
      HttpServletRequest request) {
    return record(
        "CLIENT_TELEMETRY",
        name,
        outcome,
        "CLIENT_ERROR".equals(name) ? "WARNING" : "INFO",
        RequestIdFilter.get(request),
        actor,
        tenant,
        duration,
        request.getRemoteAddr(),
        request.getHeader("User-Agent"));
  }

  public boolean recordBusinessAction(
      String name, String actor, String tenant, HttpServletRequest request) {
    return record(
        "BUSINESS_ACTION",
        name,
        "SUCCESS",
        "INFO",
        RequestIdFilter.get(request),
        actor,
        tenant,
        null,
        request.getRemoteAddr(),
        request.getHeader("User-Agent"));
  }

  private boolean record(
      String type,
      String name,
      String outcome,
      String severity,
      String requestId,
      String actor,
      String tenant,
      Long duration,
      String ip,
      String userAgent) {
    try {
      jdbc.update(
          "EXEC audit.usp_RecordSystemEvent @ApplicationId=?, @EventType=?, @EventName=?, @Outcome=?, @Severity=?, @RequestId=?, @ActorId=?, @TenantId=?, @DurationMs=?, @SourceIp=?, @UserAgent=?, @MetadataJson=?",
          applicationId,
          cut(type, 80),
          cut(name, 300),
          cut(outcome, 30),
          cut(severity, 20),
          cut(requestId, 64),
          cut(actor, 255),
          cut(tenant, 80),
          duration,
          cut(ip, 64),
          cut(userAgent, 512),
          null);
      return true;
    } catch (DataAccessException exception) {
      log.warn(
          "system_log_write_failed applicationId={} eventType={} eventName={} requestId={}",
          applicationId,
          type,
          name,
          requestId);
      return false;
    }
  }

  private String cut(String value, int max) {
    if (value == null || value.isBlank()) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }
}
