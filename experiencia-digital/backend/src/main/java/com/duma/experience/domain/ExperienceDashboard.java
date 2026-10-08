package com.duma.experience.domain;

import com.duma.core.domain.CoverageStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ExperienceDashboard {

  private ExperienceDashboard() {}

  public record Response(
      Instant generatedAt, LocalDate from, LocalDate to, List<TenantResult> tenants) {}

  public record TenantResult(
      String tenantId,
      String tenantName,
      CoverageStatus coverageStatus,
      List<String> missingSources,
      PeriodMetrics current,
      PeriodMetrics previous,
      List<UserDaily> userDaily,
      List<UserExperience> users,
      List<UserTimeline> userTimeline,
      List<EndpointSummary> endpoints,
      List<AvailabilityDaily> availabilityDaily,
      String errorCode) {}

  public record PeriodMetrics(UserMetrics users, AvailabilityMetrics availability) {
    public long observedRows() {
      return users.evaluatedUserDays() + availability.observedServiceDays();
    }
  }

  public record UserMetrics(
      long evaluatedUserDays,
      long sessionUserDays,
      long completeInteractions,
      Double avgSessionSeconds,
      Double avgLatencyMs,
      Double maxP95LatencyMs,
      long slowUserDays) {
    public static UserMetrics empty() {
      return new UserMetrics(0, 0, 0, null, null, null, 0);
    }
  }

  public record AvailabilityMetrics(
      long observedServiceDays,
      Double avgUptimePercentage,
      Double avgLatencySeconds,
      Double maxP95LatencySeconds,
      long timeoutDays,
      long currentDownServices,
      LocalDate latestDate) {
    public static AvailabilityMetrics empty() {
      return new AvailabilityMetrics(0, null, null, null, 0, 0, null);
    }
  }

  public record UserDaily(
      LocalDate metricDate,
      long usersEvaluated,
      long connectedUsers,
      long completeInteractions,
      long totalTimeConnected,
      Double avgLatencyMs,
      Double maxP95LatencyMs) {}

  public record UserExperience(
      String userId,
      String displayName,
      String userName,
      String position,
      long daysEvaluated,
      long completeInteractions,
      long timeConnectedSeconds,
      Double avgSessionSeconds,
      Double maxSessionSeconds,
      Double avgLatencyMs,
      Double p95LatencyMs,
      LocalDate lastActivityDate) {}

  public record UserTimeline(
      String userId,
      LocalDate metricDate,
      String displayName,
      String userName,
      boolean madeCompleteInteraction,
      long timeConnectedSeconds,
      Double avgLatencyMs,
      Double p95LatencyMs) {}

  public record EndpointSummary(
      String url,
      Double uptimePercentage,
      Double avgLatencySeconds,
      Double latency95thPercentileSeconds,
      long upDays,
      long timeoutDays,
      long observedDays,
      boolean currentIsUp,
      boolean currentTimeouts,
      LocalDate latestDate) {}

  public record AvailabilityDaily(
      String url,
      LocalDate metricDate,
      Double uptimePercentage,
      Double avgLatencySeconds,
      Double latency95thPercentileSeconds,
      boolean up,
      boolean timeoutsPresent) {}
}
