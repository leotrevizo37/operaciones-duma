package com.duma.experience.data;

import com.duma.core.config.ModuleProperties;
import com.duma.core.domain.CoverageResolver;
import com.duma.core.domain.CoverageStatus;
import com.duma.experience.config.TenantDataSourceRegistry;
import com.duma.experience.domain.ExperienceDashboard;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExperienceRepository {

  private static final Logger log = LoggerFactory.getLogger(ExperienceRepository.class);
  private static final String USER_SOURCE = "observability.factSidonUserUsage";
  private static final String USER_DIMENSION = "observability.dimAspNetUsers";
  private static final String AVAILABILITY_SOURCE = "observability.factUrlAvailabilityDaily";
  private final ModuleProperties properties;
  private final TenantDataSourceRegistry registry;

  public ExperienceRepository(ModuleProperties properties, TenantDataSourceRegistry registry) {
    this.properties = properties;
    this.registry = registry;
  }

  public ExperienceDashboard.TenantResult load(String tenantId, LocalDate from, LocalDate to) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (!tenant.isEnabled() || tenant.getDatabase() == null || tenant.getDatabase().isBlank())
      return unavailable(tenantId, tenant.getDisplayName(), null);
    try {
      JdbcTemplate jdbc = registry.jdbc(tenantId);
      boolean usersPresent = objectExists(jdbc, USER_SOURCE);
      boolean userDimensionPresent = usersPresent && objectExists(jdbc, USER_DIMENSION);
      boolean availabilityPresent = objectExists(jdbc, AVAILABILITY_SOURCE);
      List<String> missingSources = new ArrayList<>();
      if (!usersPresent) missingSources.add(USER_SOURCE);
      if (usersPresent && !userDimensionPresent) missingSources.add(USER_DIMENSION);
      if (!availabilityPresent) missingSources.add(AVAILABILITY_SOURCE);

      long days = ChronoUnit.DAYS.between(from, to) + 1;
      LocalDate previousTo = from.minusDays(1);
      LocalDate previousFrom = previousTo.minusDays(days - 1);
      ExperienceDashboard.PeriodMetrics current =
          loadPeriod(jdbc, from, to, usersPresent, availabilityPresent);
      ExperienceDashboard.PeriodMetrics previous =
          loadPeriod(jdbc, previousFrom, previousTo, usersPresent, availabilityPresent);
      // Two independent sources, either of which grants coverage; the shared rule takes one
      // source and its readability, so they collapse here rather than travelling as a pair.
      boolean anySource = usersPresent || availabilityPresent;
      CoverageStatus status =
          CoverageResolver.resolve(anySource, anySource, current.observedRows());
      return new ExperienceDashboard.TenantResult(
          tenantId,
          tenant.getDisplayName(),
          status,
          List.copyOf(missingSources),
          current,
          previous,
          usersPresent ? loadUserDaily(jdbc, from, to) : List.of(),
          usersPresent ? loadUserExperience(jdbc, from, to, userDimensionPresent) : List.of(),
          usersPresent ? loadUserTimeline(jdbc, from, to, userDimensionPresent) : List.of(),
          availabilityPresent ? loadEndpoints(jdbc, from, to) : List.of(),
          availabilityPresent ? loadAvailabilityDaily(jdbc, from, to) : List.of(),
          null);
    } catch (DataAccessException exception) {
      log.warn(
          "tenant_query_failed module=experiencia-digital tenant={} error={}",
          tenantId,
          exception.getClass().getSimpleName());
      return unavailable(tenantId, tenant.getDisplayName(), "TENANT_QUERY_FAILED");
    } catch (RuntimeException exception) {
      log.warn(
          "tenant_configuration_failed module=experiencia-digital tenant={} error={}",
          tenantId,
          exception.getClass().getSimpleName());
      return unavailable(tenantId, tenant.getDisplayName(), "TENANT_QUERY_FAILED");
    }
  }

  public Freshness freshness(String tenantId) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (!tenant.isEnabled() || tenant.getDatabase() == null || tenant.getDatabase().isBlank())
      return new Freshness(
          tenantId, tenant.getDisplayName(), "factUrlAvailabilityDaily", null, null);
    try {
      List<Freshness> rows =
          registry
              .jdbc(tenantId)
              .query(
                  "SELECT LastRunStatus, LastLoadedAt FROM ctl.IngestionControl WHERE IngestionName = ?",
                  (resultSet, rowNumber) ->
                      new Freshness(
                          tenantId,
                          tenant.getDisplayName(),
                          "factUrlAvailabilityDaily",
                          resultSet.getString("LastRunStatus"),
                          resultSet.getTimestamp("LastLoadedAt") == null
                              ? null
                              : resultSet.getTimestamp("LastLoadedAt").toInstant()),
                  "factUrlAvailabilityDaily");
      return rows.isEmpty()
          ? new Freshness(tenantId, tenant.getDisplayName(), "factUrlAvailabilityDaily", null, null)
          : rows.get(0);
    } catch (DataAccessException | IllegalArgumentException exception) {
      log.warn("freshness_query_failed module=experiencia-digital tenant={}", tenantId);
      return new Freshness(tenantId, tenant.getDisplayName(), "factUrlAvailabilityDaily", null, null);
    }
  }

  public record Freshness(
      String tenantId,
      String tenantName,
      String ingestionName,
      String lastRunStatus,
      java.time.Instant lastLoadedAt) {}

  private ExperienceDashboard.PeriodMetrics loadPeriod(
      JdbcTemplate jdbc,
      LocalDate from,
      LocalDate to,
      boolean usersPresent,
      boolean availabilityPresent) {
    ExperienceDashboard.UserMetrics users =
        usersPresent ? loadUsers(jdbc, from, to) : ExperienceDashboard.UserMetrics.empty();
    ExperienceDashboard.AvailabilityMetrics availability =
        availabilityPresent
            ? loadAvailability(jdbc, from, to)
            : ExperienceDashboard.AvailabilityMetrics.empty();
    return new ExperienceDashboard.PeriodMetrics(users, availability);
  }

  private ExperienceDashboard.UserMetrics loadUsers(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForObject(
        """
            SELECT
                COUNT_BIG(*) AS EvaluatedUserDays,
                COALESCE(SUM(CASE WHEN HasConnected = 1 THEN 1 ELSE 0 END), 0) AS SessionUserDays,
                COALESCE(SUM(CASE WHEN MadeCompleteInteraction = 1 THEN 1 ELSE 0 END), 0) AS CompleteInteractions,
                AVG(CASE WHEN HasConnected = 1 AND TimeConnected > 0 THEN CAST(TimeConnected AS float) END) AS AvgSessionSeconds,
                AVG(CASE WHEN HasConnected = 1 AND AvgLatency > 0 THEN CAST(AvgLatency AS float) END) AS AvgLatencyMs,
                MAX(CAST(Latency95thPercentile AS float)) AS MaxP95LatencyMs,
                COALESCE(SUM(CASE WHEN HasConnected = 1 AND AvgLatency >= 2000 THEN 1 ELSE 0 END), 0) AS SlowUserDays
            FROM observability.factSidonUserUsage
            WHERE [Date] BETWEEN ? AND ?
            """,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.UserMetrics(
                resultSet.getLong("EvaluatedUserDays"),
                resultSet.getLong("SessionUserDays"),
                resultSet.getLong("CompleteInteractions"),
                nullableDouble(resultSet, "AvgSessionSeconds"),
                nullableDouble(resultSet, "AvgLatencyMs"),
                nullableDouble(resultSet, "MaxP95LatencyMs"),
                resultSet.getLong("SlowUserDays")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private ExperienceDashboard.AvailabilityMetrics loadAvailability(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForObject(
        """
            WITH filtered AS (
                SELECT *
                FROM observability.factUrlAvailabilityDaily
                WHERE [Date] BETWEEN ? AND ?
            ), latest AS (
                SELECT *, ROW_NUMBER() OVER (PARTITION BY Url ORDER BY [Date] DESC, ModifiedAt DESC) AS rn
                FROM filtered
            )
            SELECT
                COUNT_BIG(*) AS ObservedServiceDays,
                AVG(CAST(UptimePercentage AS float)) AS AvgUptimePercentage,
                AVG(CAST(AvgLatencySeconds AS float)) AS AvgLatencySeconds,
                MAX(CAST(Latency95thPercentileSeconds AS float)) AS MaxP95LatencySeconds,
                COALESCE(SUM(CASE WHEN TimeoutsPresent = 1 THEN 1 ELSE 0 END), 0) AS TimeoutDays,
                (SELECT COUNT_BIG(*) FROM latest WHERE rn = 1 AND IsUp = 0) AS CurrentDownServices,
                MAX([Date]) AS LatestDate
            FROM filtered
            """,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.AvailabilityMetrics(
                resultSet.getLong("ObservedServiceDays"),
                nullableDouble(resultSet, "AvgUptimePercentage"),
                nullableDouble(resultSet, "AvgLatencySeconds"),
                nullableDouble(resultSet, "MaxP95LatencySeconds"),
                resultSet.getLong("TimeoutDays"),
                resultSet.getLong("CurrentDownServices"),
                resultSet.getDate("LatestDate") == null
                    ? null
                    : resultSet.getDate("LatestDate").toLocalDate()),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ExperienceDashboard.UserDaily> loadUserDaily(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
            SELECT [Date] AS MetricDate,
                   COUNT_BIG(*) AS UsersEvaluated,
                   COALESCE(SUM(CASE WHEN HasConnected = 1 THEN 1 ELSE 0 END), 0) AS ConnectedUsers,
                   COALESCE(SUM(CASE WHEN MadeCompleteInteraction = 1 THEN 1 ELSE 0 END), 0) AS CompleteInteractions,
                   COALESCE(SUM(TimeConnected), 0) AS TotalTimeConnected,
                   AVG(CASE WHEN HasConnected = 1 AND AvgLatency > 0 THEN CAST(AvgLatency AS float) END) AS AvgLatencyMs,
                   MAX(CAST(Latency95thPercentile AS float)) AS MaxP95LatencyMs
            FROM observability.factSidonUserUsage
            WHERE [Date] BETWEEN ? AND ?
            GROUP BY [Date]
            ORDER BY [Date]
            """,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.UserDaily(
                resultSet.getDate("MetricDate").toLocalDate(),
                resultSet.getLong("UsersEvaluated"),
                resultSet.getLong("ConnectedUsers"),
                resultSet.getLong("CompleteInteractions"),
                resultSet.getLong("TotalTimeConnected"),
                nullableDouble(resultSet, "AvgLatencyMs"),
                nullableDouble(resultSet, "MaxP95LatencyMs")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ExperienceDashboard.UserExperience> loadUserExperience(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, boolean dimensionPresent) {
    String displayName =
        dimensionPresent
            ? "COALESCE(NULLIF(LTRIM(RTRIM(CONCAT(users.Name, ' ', users.LastName))), ''), users.UserName, CONVERT(varchar(36), usage.UserId))"
            : "CONVERT(varchar(36), usage.UserId)";
    String userName = dimensionPresent ? "users.UserName" : "CAST(NULL AS nvarchar(256))";
    String position = dimensionPresent ? "users.Position" : "CAST(NULL AS nvarchar(256))";
    String join =
        dimensionPresent
            ? "LEFT JOIN observability.dimAspNetUsers AS users ON users.Id = usage.UserId"
            : "";
    String group =
        dimensionPresent
            ? "usage.UserId, users.UserName, users.Name, users.LastName, users.Position"
            : "usage.UserId";
    String sql =
        """
            SELECT TOP (100) CONVERT(varchar(36), usage.UserId) AS UserId,
                   %s AS DisplayName,
                   %s AS UserName,
                   %s AS Position,
                   COUNT_BIG(*) AS DaysEvaluated,
                   COALESCE(SUM(CASE WHEN usage.MadeCompleteInteraction = 1 THEN 1 ELSE 0 END), 0) AS CompleteInteractions,
                   COALESCE(SUM(usage.TimeConnected), 0) AS TimeConnectedSeconds,
                   AVG(CASE WHEN usage.HasConnected = 1 AND usage.TimeConnected > 0 THEN CAST(usage.TimeConnected AS float) END) AS AvgSessionSeconds,
                   MAX(CAST(usage.TimeConnected AS float)) AS MaxSessionSeconds,
                   AVG(CASE WHEN usage.HasConnected = 1 AND usage.AvgLatency > 0 THEN CAST(usage.AvgLatency AS float) END) AS AvgLatencyMs,
                   MAX(CAST(usage.Latency95thPercentile AS float)) AS P95LatencyMs,
                   MAX(CASE WHEN usage.HasConnected = 1 THEN usage.[Date] END) AS LastActivityDate
            FROM observability.factSidonUserUsage AS usage
            %s
            WHERE usage.[Date] BETWEEN ? AND ?
            GROUP BY %s
            ORDER BY TimeConnectedSeconds DESC, DisplayName
            """
            .formatted(displayName, userName, position, join, group);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.UserExperience(
                resultSet.getString("UserId"),
                resultSet.getString("DisplayName"),
                resultSet.getString("UserName"),
                resultSet.getString("Position"),
                resultSet.getLong("DaysEvaluated"),
                resultSet.getLong("CompleteInteractions"),
                resultSet.getLong("TimeConnectedSeconds"),
                nullableDouble(resultSet, "AvgSessionSeconds"),
                nullableDouble(resultSet, "MaxSessionSeconds"),
                nullableDouble(resultSet, "AvgLatencyMs"),
                nullableDouble(resultSet, "P95LatencyMs"),
                resultSet.getDate("LastActivityDate") == null
                    ? null
                    : resultSet.getDate("LastActivityDate").toLocalDate()),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ExperienceDashboard.UserTimeline> loadUserTimeline(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, boolean dimensionPresent) {
    String displayName =
        dimensionPresent
            ? "COALESCE(NULLIF(LTRIM(RTRIM(CONCAT(users.Name, ' ', users.LastName))), ''), users.UserName, CONVERT(varchar(36), usage.UserId))"
            : "CONVERT(varchar(36), usage.UserId)";
    String userName = dimensionPresent ? "users.UserName" : "CAST(NULL AS nvarchar(256))";
    String join =
        dimensionPresent
            ? "LEFT JOIN observability.dimAspNetUsers AS users ON users.Id = usage.UserId"
            : "";
    String sql =
        """
            SELECT CONVERT(varchar(36), usage.UserId) AS UserId,
                   usage.[Date] AS MetricDate,
                   %s AS DisplayName,
                   %s AS UserName,
                   CAST(usage.MadeCompleteInteraction AS int) AS MadeCompleteInteraction,
                   usage.TimeConnected AS TimeConnectedSeconds,
                   CAST(usage.AvgLatency AS float) AS AvgLatencyMs,
                   CAST(usage.Latency95thPercentile AS float) AS P95LatencyMs
            FROM observability.factSidonUserUsage AS usage
            %s
            WHERE usage.[Date] BETWEEN ? AND ?
            ORDER BY usage.UserId, usage.[Date]
            """
            .formatted(displayName, userName, join);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.UserTimeline(
                resultSet.getString("UserId"),
                resultSet.getDate("MetricDate").toLocalDate(),
                resultSet.getString("DisplayName"),
                resultSet.getString("UserName"),
                resultSet.getInt("MadeCompleteInteraction") == 1,
                resultSet.getLong("TimeConnectedSeconds"),
                nullableDouble(resultSet, "AvgLatencyMs"),
                nullableDouble(resultSet, "P95LatencyMs")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ExperienceDashboard.EndpointSummary> loadEndpoints(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
            WITH filtered AS (
                SELECT * FROM observability.factUrlAvailabilityDaily
                WHERE [Date] BETWEEN ? AND ?
            ), latest AS (
                SELECT *, ROW_NUMBER() OVER (PARTITION BY Url ORDER BY [Date] DESC, ModifiedAt DESC) AS rn
                FROM filtered
            )
            SELECT filtered.Url,
                   AVG(CAST(filtered.UptimePercentage AS float)) AS UptimePercentage,
                   AVG(CAST(filtered.AvgLatencySeconds AS float)) AS AvgLatencySeconds,
                   AVG(CAST(filtered.Latency95thPercentileSeconds AS float)) AS Latency95thPercentileSeconds,
                   COALESCE(SUM(CASE WHEN filtered.IsUp = 1 THEN 1 ELSE 0 END), 0) AS UpDays,
                   COALESCE(SUM(CASE WHEN filtered.TimeoutsPresent = 1 THEN 1 ELSE 0 END), 0) AS TimeoutDays,
                   COUNT_BIG(*) AS ObservedDays,
                   CAST(latest.IsUp AS int) AS CurrentIsUp,
                   CAST(latest.TimeoutsPresent AS int) AS CurrentTimeouts,
                   latest.[Date] AS LatestDate
            FROM filtered
            INNER JOIN latest ON latest.Url = filtered.Url AND latest.rn = 1
            GROUP BY filtered.Url, latest.IsUp, latest.TimeoutsPresent, latest.[Date]
            ORDER BY UptimePercentage, filtered.Url
            """,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.EndpointSummary(
                resultSet.getString("Url"),
                nullableDouble(resultSet, "UptimePercentage"),
                nullableDouble(resultSet, "AvgLatencySeconds"),
                nullableDouble(resultSet, "Latency95thPercentileSeconds"),
                resultSet.getLong("UpDays"),
                resultSet.getLong("TimeoutDays"),
                resultSet.getLong("ObservedDays"),
                resultSet.getInt("CurrentIsUp") == 1,
                resultSet.getInt("CurrentTimeouts") == 1,
                resultSet.getDate("LatestDate").toLocalDate()),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ExperienceDashboard.AvailabilityDaily> loadAvailabilityDaily(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
            SELECT Url, [Date] AS MetricDate,
                   CAST(UptimePercentage AS float) AS UptimePercentage,
                   CAST(AvgLatencySeconds AS float) AS AvgLatencySeconds,
                   CAST(Latency95thPercentileSeconds AS float) AS Latency95thPercentileSeconds,
                   CAST(IsUp AS int) AS IsUp,
                   CAST(TimeoutsPresent AS int) AS TimeoutsPresent
            FROM observability.factUrlAvailabilityDaily
            WHERE [Date] BETWEEN ? AND ?
            ORDER BY [Date], Url
            """,
        (resultSet, rowNumber) ->
            new ExperienceDashboard.AvailabilityDaily(
                resultSet.getString("Url"),
                resultSet.getDate("MetricDate").toLocalDate(),
                nullableDouble(resultSet, "UptimePercentage"),
                nullableDouble(resultSet, "AvgLatencySeconds"),
                nullableDouble(resultSet, "Latency95thPercentileSeconds"),
                resultSet.getInt("IsUp") == 1,
                resultSet.getInt("TimeoutsPresent") == 1),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private boolean objectExists(JdbcTemplate jdbc, String objectName) {
    Integer value =
        jdbc.queryForObject(
            "SELECT CASE WHEN OBJECT_ID(?, N'U') IS NULL THEN 0 ELSE 1 END",
            Integer.class,
            objectName);
    return value != null && value == 1;
  }

  private Double nullableDouble(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    double value = resultSet.getDouble(column);
    return resultSet.wasNull() ? null : value;
  }

  private ExperienceDashboard.TenantResult unavailable(
      String tenantId, String tenantName, String errorCode) {
    ExperienceDashboard.PeriodMetrics empty =
        new ExperienceDashboard.PeriodMetrics(
            ExperienceDashboard.UserMetrics.empty(),
            ExperienceDashboard.AvailabilityMetrics.empty());
    return new ExperienceDashboard.TenantResult(
        tenantId,
        tenantName,
        CoverageStatus.UNAVAILABLE,
        List.of(),
        empty,
        empty,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        errorCode);
  }
}
