package com.duma.readings.data;

import com.duma.core.config.ModuleProperties;
import com.duma.core.domain.CoverageResolver;
import com.duma.core.domain.CoverageStatus;
import com.duma.readings.config.TenantDataSourceRegistry;
import com.duma.readings.domain.ReadingsDashboard;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadingsRepository {
  private static final Logger log = LoggerFactory.getLogger(ReadingsRepository.class);
  private static final String FACT = "observability.factRedingsAudits";
  private static final String MEASUREMENTS = "dwh.factReadingsMeasurement";
  private static final String DIMENSION = "dwh.dimSidonProdDimensions";
  private static final String SERVING_SENSORS = "serving.vwSenseSensors";
  private static final DateTimeFormatter LOCAL_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
  private final ModuleProperties properties;
  private final TenantDataSourceRegistry registry;

  public ReadingsRepository(ModuleProperties properties, TenantDataSourceRegistry registry) {
    this.properties = properties;
    this.registry = registry;
  }

  public ReadingsDashboard.TenantResult load(String tenantId, LocalDate from, LocalDate to) {
    return load(tenantId, from, to, false);
  }

  public ReadingsDashboard.TenantResult load(String tenantId, LocalDate from, LocalDate to, boolean includeDiagnostics) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (!tenant.isEnabled() || tenant.getDatabase() == null || tenant.getDatabase().isBlank())
      return unavailable(tenantId, tenant.getDisplayName(), null);
    String stage = "data_source";
    try {
      JdbcTemplate jdbc = registry.jdbc(tenantId);
      ServingSource servingSource = ServingSource.forPeriod(from, to);
      boolean sensorsViewReadable =
          viewExists(jdbc, SERVING_SENSORS) && hasSelect(jdbc, SERVING_SENSORS) == 1;
      boolean servingReadable =
          viewExists(jdbc, servingSource.view())
              && hasSelect(jdbc, servingSource.view()) == 1;
      if (servingReadable && sensorsViewReadable) {
        stage = "serving_views";
        return loadServing(
            jdbc,
            tenantId,
            tenant.getDisplayName(),
            from,
            to,
            servingSource,
            hasColumn(jdbc, SERVING_SENSORS, "PhysicalIdentifier"), includeDiagnostics);
      }
      stage = "fact_presence";
      boolean factPresent = objectExists(jdbc, FACT);
      boolean factReadable = factPresent && hasSelect(jdbc, FACT) == 1;
      // Avoid metadata queries when the preferred fact table is readable.
      boolean measurementsReadable =
          !factReadable && objectExists(jdbc, MEASUREMENTS) && hasSelect(jdbc, MEASUREMENTS) == 1;
      stage = "dimension_presence";
      boolean dimensionPresent =
          objectExists(jdbc, DIMENSION) && hasSelect(jdbc, DIMENSION) == 1;
      List<String> missing = new ArrayList<>();
      if (!factReadable) missing.add(FACT);
      if (!dimensionPresent) missing.add(DIMENSION);
      if (!factReadable && measurementsReadable) {
        stage = "measurement_fallback";
        return loadMeasurements(
            jdbc, tenantId, tenant.getDisplayName(), from, to, dimensionPresent, missing);
      }
      if (!factReadable)
        return new ReadingsDashboard.TenantResult(
            tenantId,
            tenant.getDisplayName(),
            CoverageResolver.resolve(factPresent, factReadable, 0),
            List.copyOf(missing),
            ReadingsDashboard.Summary.empty(),
            ReadingsDashboard.Summary.empty(),
            List.of(),
            List.of(),
            List.of(),
            factPresent ? "TENANT_QUERY_FAILED" : null);
      long days = ChronoUnit.DAYS.between(from, to) + 1;
      LocalDate previousTo = from.minusDays(1);
      LocalDate previousFrom = previousTo.minusDays(days - 1);
      stage = "current_summary";
      ReadingsDashboard.Summary current = summary(jdbc, from, to);
      stage = "previous_summary";
      ReadingsDashboard.Summary previous = summary(jdbc, previousFrom, previousTo);
      CoverageStatus status = CoverageResolver.resolve(true, true, current.sensorsObserved());
      List<ReadingsDashboard.SensorException> exceptions =
          status == CoverageStatus.AVAILABLE
              ? exceptions(jdbc, from, to, dimensionPresent)
              : List.of();
      stage = "details";
      return new ReadingsDashboard.TenantResult(
          tenantId,
          tenant.getDisplayName(),
          status,
          List.copyOf(missing),
          current,
          previous,
          exceptions,
          status == CoverageStatus.AVAILABLE ? timeline(jdbc, from, to) : List.of(),
          status == CoverageStatus.AVAILABLE
              ? sensors(jdbc, from, to, dimensionPresent)
              : List.of(),
          null);
    } catch (DataAccessException exception) {
      Throwable cause = exception.getMostSpecificCause();
      if (cause instanceof SQLException sqlException) {
        log.warn(
            "tenant_query_failed module=lecturas tenant={} stage={} error={} sql_state={} sql_error={}",
            tenantId,
            stage,
            exception.getClass().getSimpleName(),
            sqlException.getSQLState(),
            sqlException.getErrorCode());
      } else {
        log.warn(
            "tenant_query_failed module=lecturas tenant={} stage={} error={}",
            tenantId,
            stage,
            exception.getClass().getSimpleName());
      }
      return unavailable(tenantId, tenant.getDisplayName(), "TENANT_QUERY_FAILED");
    } catch (IllegalArgumentException exception) {
      log.warn(
          "tenant_query_failed module=lecturas tenant={} stage={} error={}",
          tenantId,
          stage,
          exception.getClass().getSimpleName());
      return unavailable(tenantId, tenant.getDisplayName(), "TENANT_QUERY_FAILED");
    }
  }

  public Freshness freshness(String tenantId) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (!tenant.isEnabled() || tenant.getDatabase() == null || tenant.getDatabase().isBlank())
      return new Freshness(tenantId, tenant.getDisplayName(), "factRedingsAudits", null, null);
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
                          "factRedingsAudits",
                          resultSet.getString("LastRunStatus"),
                          instant(resultSet.getTimestamp("LastLoadedAt"))),
                  "factRedingsAudits");
      return rows.isEmpty()
          ? new Freshness(tenantId, tenant.getDisplayName(), "factRedingsAudits", null, null)
          : rows.get(0);
    } catch (DataAccessException | IllegalArgumentException exception) {
      log.warn("freshness_query_failed module=lecturas tenant={}", tenantId);
      return new Freshness(tenantId, tenant.getDisplayName(), "factRedingsAudits", null, null);
    }
  }

  public record Freshness(
      String tenantId,
      String tenantName,
      String ingestionName,
      String lastRunStatus,
      java.time.Instant lastLoadedAt) {}

  private ReadingsDashboard.TenantResult loadServing(
      JdbcTemplate jdbc,
      String tenantId,
      String tenantName,
      LocalDate from,
      LocalDate to,
      ServingSource source,
      boolean physicalIdentifierPresent, boolean includeDiagnostics) {
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    LocalDate previousTo = from.minusDays(1);
    LocalDate previousFrom = previousTo.minusDays(days - 1);
    ReadingsDashboard.Summary current = servingSummary(jdbc, source, from, to);
    ReadingsDashboard.Summary previous = servingSummary(jdbc, source, previousFrom, previousTo);
    CoverageStatus status =
        current.sensorsObserved() == 0 ? CoverageStatus.NO_DATA : CoverageStatus.AVAILABLE;
    log.info(
        "tenant_source_selected module=lecturas tenant={} source={} coverage={}",
        tenantId,
        source.view(),
        status);
    List<String> missing = new ArrayList<>();
    List<ReadingsDashboard.Diagnostics> diagnostics = includeDiagnostics && status == CoverageStatus.AVAILABLE
        ? diagnostics(jdbc, source, from, to, missing) : List.of();
    return new ReadingsDashboard.TenantResult(
        tenantId,
        tenantName,
        status,
        List.copyOf(missing),
        current,
        previous,
        status == CoverageStatus.AVAILABLE
            ? servingExceptions(jdbc, source, from, to)
            : List.of(),
        status == CoverageStatus.AVAILABLE ? servingTimeline(jdbc, source, from, to) : List.of(),
        status == CoverageStatus.AVAILABLE
            ? servingSensors(jdbc, source, from, to, physicalIdentifierPresent)
            : List.of(),
        null, diagnostics);
  }

  private List<ReadingsDashboard.Diagnostics> diagnostics(
      JdbcTemplate jdbc, ServingSource period, LocalDate from, LocalDate to, List<String> missing) {
    String hourly = ServingSource.HOURLY.view();
    if (!viewExists(jdbc, hourly) || hasSelect(jdbc, hourly) != 1
        || !hasColumn(jdbc, hourly, "DevEui")) {
      missing.add(hourly);
      return List.of();
    }
    LocalDate start = period == ServingSource.WEEKLY
        ? from.minusDays(from.getDayOfWeek().getValue() - 1) : from;
    LocalDate end = period == ServingSource.WEEKLY
        ? to.plusDays(8 - to.getDayOfWeek().getValue()) : to.plusDays(1);
    Map<String, ReadingsDashboard.Diagnostics> groups = new LinkedHashMap<>();
    Map<String, List<String>> columns = new LinkedHashMap<>();
    columns.put("observability.factChirpStackDeviceEventMetrics", List.of(
        "CapturedUplinkCount", "ObservedGatewayCount", "MultiGatewayUplinkCount",
        "EventLogCountsJson", "RadioProfileCountsJson", "RadioFieldQualityJson", "ExtractedAt"));
    columns.put("observability.factChirpStackDeviceGatewayMetrics", List.of(
        "GatewayId", "GatewayReceptionCount", "ReceptionShare", "AverageRssi", "MinRssi",
        "MaxRssi", "AverageSnr", "MinSnr", "MaxSnr", "RadioReceptionStatsJson",
        "ReceptionFieldQualityJson", "ExtractedAt"));
    columns.put("observability.factChirpStackDeviceMetrics", List.of(
        "MetricsAvailable", "LinkErrorCount", "LinkErrorCountsJson", "ExtractedAt"));
    columns.put("observability.factChirpStackGatewayMetrics", List.of(
        "GatewayId", "MetricsAvailable", "TxOkCount", "TxErrorCount",
        "TxPacketStatusCountsJson", "ExtractedAt"));
    JsonMapper mapper = JsonMapper.builder().build();
    for (var entry : columns.entrySet()) {
      String table = entry.getKey();
      if (!objectExists(jdbc, table) || hasSelect(jdbc, table) != 1) {
        missing.add(table);
        continue;
      }
      boolean gateway = table.equals("observability.factChirpStackGatewayMetrics");
      String receptions = "observability.factChirpStackDeviceGatewayMetrics";
      if (gateway && (!objectExists(jdbc, receptions) || hasSelect(jdbc, receptions) != 1)) {
        missing.add(receptions);
        continue;
      }
      List<String> available = entry.getValue().stream()
          .filter(column -> hasColumn(jdbc, table, column)).toList();
      String selected = String.join(", ", available.stream().map(column -> "m." + column).toList());
      String sql = "SELECT CONVERT(varchar(36), h.SensorId) AS SensorId, h.LocalTimeSpan, "
          + "m.TimeSpan, " + (gateway ? "r.DevEui" : "m.DevEui") + (selected.isEmpty() ? "" : ", " + selected)
          + " FROM " + hourly + " h "
          + (gateway ? "JOIN " + receptions + " r ON r.DevEui = h.DevEui AND r.TimeSpan = h.TimeSpan JOIN "
              + table + " m ON m.GatewayId = r.GatewayId AND m.TimeSpan = r.TimeSpan AND m.TenantId = r.TenantId"
              : "JOIN " + table + " m ON m.DevEui = h.DevEui AND m.TimeSpan = h.TimeSpan")
          + " WHERE h.LocalTimeSpan >= ? AND h.LocalTimeSpan < ?"
          + " ORDER BY h.SensorId, h.TimeSpan";
      jdbc.query(sql, resultSet -> {
        String sensorId = resultSet.getString("SensorId");
        var local = resultSet.getTimestamp("LocalTimeSpan").toLocalDateTime();
        if (period != ServingSource.HOURLY) {
          LocalDate date = local.toLocalDate();
          if (period == ServingSource.WEEKLY)
            date = date.minusDays(date.getDayOfWeek().getValue() - 1);
          local = date.atStartOfDay();
        }
        String localPeriod = local.format(LOCAL_TIME_FORMAT);
        String key = sensorId + "|" + localPeriod + "|" + table;
        var group = groups.computeIfAbsent(key, ignored ->
            new ReadingsDashboard.Diagnostics(sensorId, localPeriod, table, new ArrayList<>()));
        var hour = mapper.createObjectNode();
        var metadata = resultSet.getMetaData();
        for (int index = 3; index <= metadata.getColumnCount(); index++) {
          String column = metadata.getColumnLabel(index);
          String name = Character.toLowerCase(column.charAt(0)) + column.substring(1);
          Object value = resultSet.getObject(index);
          JsonNode node;
          if (column.endsWith("Json")) {
            name = name.substring(0, name.length() - 4);
            try {
              node = value == null ? mapper.nullNode() : mapper.readTree(value.toString());
            } catch (tools.jackson.core.JacksonException exception) {
              throw new IllegalArgumentException("Invalid observability JSON", exception);
            }
            boolean array = column.equals("RadioProfileCountsJson") || column.equals("RadioReceptionStatsJson");
            if (!node.isNull() && (array ? !node.isArray() : !node.isObject()))
              throw new IllegalArgumentException("Invalid observability JSON shape");
            if (array && !node.isNull()) {
              for (JsonNode element : node)
                if (!element.isObject()) throw new IllegalArgumentException("Invalid observability profile");
            }
          } else {
            if (value instanceof Timestamp timestamp) value = instant(timestamp).toString();
            node = mapper.valueToTree(value);
          }
          hour.set(name, node);
        }
        group.hours().add(hour);
      }, Date.valueOf(start), Date.valueOf(end));
    }
    return List.copyOf(groups.values());
  }

  private ReadingsDashboard.Summary servingSummary(
      JdbcTemplate jdbc, ServingSource source, LocalDate from, LocalDate to) {
    String late = optionalHealthColumn(jdbc, source.view(), "HasLateReadings", "0", "");
    String lost = optionalHealthColumn(jdbc, source.view(), "IsConnectionLost", "CASE WHEN ReadingsCount = 0 THEN 1 ELSE 0 END", "");
    String lastReading = optionalHealthColumn(jdbc, source.view(), "LastReadingAt", "CAST(NULL AS datetime2)", "");
    String sql =
        """
            WITH latest AS (
                SELECT SensorId, %s AS PeriodStart, %s AS HasLateReadings, %s AS IsConnectionLost, %s AS LastReadingAt,
                       ROW_NUMBER() OVER (PARTITION BY SensorId ORDER BY %s DESC) AS rn
                FROM %s
                WHERE %s >= ? AND %s < DATEADD(DAY, 1, ?)
            )
            SELECT COUNT_BIG(*) AS SensorsObserved,
                   COALESCE(SUM(CASE WHEN IsConnectionLost = 0 THEN 1 ELSE 0 END), 0) AS HealthySensors,
                   COALESCE(SUM(CASE WHEN IsConnectionLost = 1 THEN 1 ELSE 0 END), 0) AS DisconnectedSensors,
                   COALESCE(SUM(CASE WHEN HasLateReadings = 1 THEN 1 ELSE 0 END), 0) AS LateSensors,
                   AVG(CASE WHEN IsConnectionLost = 1 AND LastReadingAt IS NOT NULL THEN CAST(DATEDIFF(MINUTE, LastReadingAt, PeriodStart) AS float) END) AS AvgMinutesWithoutReadings,
                   MAX(CASE WHEN IsConnectionLost = 1 AND LastReadingAt IS NOT NULL THEN CAST(DATEDIFF(MINUTE, LastReadingAt, PeriodStart) AS float) END) AS MaxMinutesWithoutReadings,
                   MAX(PeriodStart) AS LatestAuditAt,
                   MAX(LastReadingAt) AS LatestReadingAt
            FROM latest WHERE rn = 1
            """
            .formatted(
                source.periodColumn(), late, lost, lastReading, source.periodColumn(), source.view(),
                source.startFilterColumn(), source.endFilterColumn());
    return jdbc.queryForObject(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.Summary(
                resultSet.getLong("SensorsObserved"),
                resultSet.getLong("HealthySensors"),
                resultSet.getLong("DisconnectedSensors"),
                resultSet.getLong("LateSensors"),
                nullableDouble(resultSet, "AvgMinutesWithoutReadings"),
                nullableDouble(resultSet, "MaxMinutesWithoutReadings"),
                instant(resultSet.getTimestamp("LatestAuditAt")),
                instant(resultSet.getTimestamp("LatestReadingAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorException> servingExceptions(
      JdbcTemplate jdbc, ServingSource source, LocalDate from, LocalDate to) {
    String late = optionalHealthColumn(jdbc, source.view(), "HasLateReadings", "0", "");
    String lost = optionalHealthColumn(jdbc, source.view(), "IsConnectionLost", "CASE WHEN ReadingsCount = 0 THEN 1 ELSE 0 END", "");
    String lastReading = optionalHealthColumn(jdbc, source.view(), "LastReadingAt", "CAST(NULL AS datetime2)", "");
    String sql =
        """
            WITH latest AS (
                SELECT *, %s AS PeriodStart, %s AS EffectiveHasLateReadings,
                       %s AS EffectiveIsConnectionLost, %s AS EffectiveLastReadingAt,
                       ROW_NUMBER() OVER (PARTITION BY SensorId ORDER BY %s DESC) AS rn
                FROM %s
                WHERE %s >= ? AND %s < DATEADD(DAY, 1, ?)
            )
            SELECT TOP (50) CONVERT(varchar(36), latest.SensorId) AS SensorId,
                   COALESCE(sensor.LocationName, N'Sin ubicacion') AS LocationName,
                   COALESCE(sensor.DeviceName, N'Sin dispositivo') AS DeviceName,
                   COALESCE(sensor.SensorName, CONVERT(varchar(36), latest.SensorId)) AS SensorName,
                   CONVERT(int, latest.EffectiveIsConnectionLost) AS IsConnectionLost,
                   CONVERT(int, latest.EffectiveHasLateReadings) AS HasLateReadings,
                   CASE WHEN latest.EffectiveIsConnectionLost = 1 AND latest.EffectiveLastReadingAt IS NOT NULL THEN CAST(DATEDIFF(MINUTE, latest.EffectiveLastReadingAt, latest.PeriodStart) AS float) END AS MinutesWithoutReadings,
                   latest.EffectiveLastReadingAt AS LastReadingAt
            FROM latest
            LEFT JOIN serving.vwSenseSensors AS sensor ON sensor.SensorId = latest.SensorId
            WHERE latest.rn = 1 AND (latest.EffectiveIsConnectionLost = 1 OR latest.EffectiveHasLateReadings = 1)
            ORDER BY latest.EffectiveIsConnectionLost DESC, MinutesWithoutReadings DESC, latest.SensorId
            """
            .formatted(
                source.periodColumn(), late, lost, lastReading, source.periodColumn(), source.view(),
                source.startFilterColumn(), source.endFilterColumn());
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorException(
                resultSet.getString("SensorId"),
                resultSet.getString("LocationName"),
                resultSet.getString("DeviceName"),
                resultSet.getString("SensorName"),
                resultSet.getInt("IsConnectionLost") == 1,
                resultSet.getInt("HasLateReadings") == 1,
                nullableDouble(resultSet, "MinutesWithoutReadings"),
                instant(resultSet.getTimestamp("LastReadingAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorTimeline> servingTimeline(
      JdbcTemplate jdbc, ServingSource source, LocalDate from, LocalDate to) {
    String view = source.view();
    String late = optionalHealthColumn(jdbc, view, "HasLateReadings", "0", "");
    String lost = optionalHealthColumn(jdbc, view, "IsConnectionLost", "CASE WHEN ReadingsCount = 0 THEN 1 ELSE 0 END", "");
    String lastReading = optionalHealthColumn(jdbc, view, "LastReadingAt", "CAST(NULL AS datetime2)", "");
    String batteryColumns = String.join(
        ", ",
        optionalColumn(jdbc, view, "BatteryLevel", "CAST(BatteryLevel AS float)", "float"),
        optionalColumn(jdbc, view, "ExternalPowerSource", "ExternalPowerSource", "bit"),
        optionalColumn(jdbc, view, "BatteryLevelSource", "BatteryLevelSource", "nvarchar(32)"),
        optionalColumn(jdbc, view, "BatteryObservedAt", "BatteryObservedAt", "datetime2"));
    String sql =
        """
            SELECT CONVERT(varchar(36), SensorId) AS SensorId,
                   %s AS PeriodStart, %s AS LocalPeriodStart,
                   ReadingsCount, CONVERT(int, %s) AS HasLateReadings,
                   CONVERT(int, %s) AS IsConnectionLost,
                   CASE WHEN %s = 1 AND %s IS NOT NULL THEN CAST(DATEDIFF(MINUTE, %s, %s) AS float) END AS MinutesWithoutReadings,
                   %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                   %s
            FROM %s AS health
            WHERE %s >= ? AND %s < DATEADD(DAY, 1, ?)
            ORDER BY SensorId, %s
            """
            .formatted(
                source.periodColumn(), source.localPeriodColumn(), late, lost,
                lost, lastReading, lastReading, source.periodColumn(),
                optionalColumn(jdbc, view, "DevEui", "DevEui", "nvarchar(255)"),
                optionalColumn(jdbc, view, "DataCoveragePercentage", "CAST(DataCoveragePercentage AS float)", "float"),
                optionalColumn(jdbc, view, "ReceivedUplinkCount", "ReceivedUplinkCount", "bigint"),
                optionalColumn(jdbc, view, "ExpectedUplinkCount", "CAST(ExpectedUplinkCount AS float)", "float"),
                optionalColumn(jdbc, view, "ChirpStackReceiptCoveragePercentage", "CAST(ChirpStackReceiptCoveragePercentage AS float)", "float"),
                optionalColumn(jdbc, view, "AverageRssi", "CAST(AverageRssi AS float)", "float"),
                optionalColumn(jdbc, view, "AverageSnr", "CAST(AverageSnr AS float)", "float"),
                optionalColumn(jdbc, view, "LinkErrorCount", "LinkErrorCount", "bigint"),
                optionalColumn(jdbc, view, "GatewayReceivedPacketCount", "GatewayReceivedPacketCount", "bigint"),
                optionalColumn(jdbc, view, "GatewayTransmittedPacketCount", "GatewayTransmittedPacketCount", "bigint"),
                optionalColumn(jdbc, view, "GatewayTxOkCount", "GatewayTxOkCount", "bigint"),
                optionalColumn(jdbc, view, "GatewayTxErrorCount", "GatewayTxErrorCount", "bigint"),
                optionalColumn(jdbc, view, "CommunicationStatus", "CommunicationStatus", "nvarchar(255)"),
                batteryColumns,
                view, source.startFilterColumn(), source.endFilterColumn(), source.periodColumn());
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorTimeline(
                resultSet.getString("SensorId"),
                instant(resultSet.getTimestamp("PeriodStart")),
                localTime(resultSet.getTimestamp("LocalPeriodStart")),
                resultSet.getLong("ReadingsCount"),
                resultSet.getInt("HasLateReadings") == 1,
                resultSet.getInt("IsConnectionLost") == 1,
                nullableDouble(resultSet, "MinutesWithoutReadings"),
                resultSet.getString("DevEui"),
                nullableDouble(resultSet, "DataCoveragePercentage"),
                nullableLong(resultSet, "ReceivedUplinkCount"),
                nullableDouble(resultSet, "ExpectedUplinkCount"),
                nullableDouble(resultSet, "ChirpStackReceiptCoveragePercentage"),
                nullableDouble(resultSet, "AverageRssi"),
                nullableDouble(resultSet, "AverageSnr"),
                nullableLong(resultSet, "LinkErrorCount"),
                nullableLong(resultSet, "GatewayReceivedPacketCount"),
                nullableLong(resultSet, "GatewayTransmittedPacketCount"),
                nullableLong(resultSet, "GatewayTxOkCount"),
                nullableLong(resultSet, "GatewayTxErrorCount"),
                nullableDouble(resultSet, "BatteryLevel"),
                nullableBoolean(resultSet, "ExternalPowerSource"),
                resultSet.getString("BatteryLevelSource"),
                instant(resultSet.getTimestamp("BatteryObservedAt")),
                resultSet.getString("CommunicationStatus")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorAggregate> servingSensors(
      JdbcTemplate jdbc,
      ServingSource source,
      LocalDate from,
      LocalDate to,
      boolean physicalIdentifierPresent) {
    String late = optionalHealthColumn(jdbc, source.view(), "HasLateReadings", "0", "health.");
    String lost = optionalHealthColumn(jdbc, source.view(), "IsConnectionLost", "CASE WHEN health.ReadingsCount = 0 THEN 1 ELSE 0 END", "health.");
    boolean devEuiPresent = hasColumn(jdbc, source.view(), "DevEui");
    String physicalIdentifier =
        physicalIdentifierPresent
            ? devEuiPresent ? "COALESCE(sensor.PhysicalIdentifier, MAX(health.DevEui))" : "sensor.PhysicalIdentifier"
            : devEuiPresent ? "MAX(health.DevEui)" : "CAST(NULL AS nvarchar(255))";
    String physicalIdentifierGroup =
        physicalIdentifierPresent ? ", sensor.PhysicalIdentifier" : "";
    String sql =
        """
            SELECT CONVERT(varchar(36), health.SensorId) AS SensorId,
                   COALESCE(sensor.LocationName, N'Sin ubicacion') AS LocationName,
                   COALESCE(sensor.DeviceName, N'Sin dispositivo') AS DeviceName,
                   COALESCE(sensor.SensorName, CONVERT(varchar(36), health.SensorId)) AS SensorName,
                   COUNT_BIG(*) AS ObservedIntervals,
                   COALESCE(SUM(CASE WHEN %s = 1 THEN 1 ELSE 0 END), 0) AS LostIntervals,
                   sensor.ModelName, %s AS PhysicalIdentifier
            FROM %s AS health
            LEFT JOIN serving.vwSenseSensors AS sensor ON sensor.SensorId = health.SensorId
            WHERE %s >= ? AND %s < DATEADD(DAY, 1, ?)
            GROUP BY health.SensorId, sensor.LocationName, sensor.DeviceName, sensor.SensorName,
                      sensor.ModelName%s
            ORDER BY LostIntervals DESC, COALESCE(SUM(CASE WHEN %s = 1 THEN 1 ELSE 0 END), 0) DESC, COALESCE(SUM(health.ReadingsCount), 0) DESC
            """
            .formatted(
                lost,
                physicalIdentifier,
                source.view(),
                source.startFilterColumn(),
                source.endFilterColumn(),
                physicalIdentifierGroup, late);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorAggregate(
                resultSet.getString("SensorId"),
                resultSet.getString("LocationName"),
                resultSet.getString("DeviceName"),
                resultSet.getString("SensorName"),
                resultSet.getLong("ObservedIntervals"),
                resultSet.getLong("LostIntervals"),
                resultSet.getString("ModelName"),
                resultSet.getString("PhysicalIdentifier")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private ReadingsDashboard.TenantResult loadMeasurements(
      JdbcTemplate jdbc,
      String tenantId,
      String tenantName,
      LocalDate from,
      LocalDate to,
      boolean dimensionPresent,
      List<String> missing) {
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    LocalDate previousTo = from.minusDays(1);
    LocalDate previousFrom = previousTo.minusDays(days - 1);
    ReadingsDashboard.Summary current = measurementSummary(jdbc, from, to);
    ReadingsDashboard.Summary previous = measurementSummary(jdbc, previousFrom, previousTo);
    CoverageStatus status = CoverageResolver.resolve(true, true, current.sensorsObserved());
    log.info(
        "tenant_source_selected module=lecturas tenant={} source={} coverage={}",
        tenantId,
        MEASUREMENTS,
        status);
    return new ReadingsDashboard.TenantResult(
        tenantId,
        tenantName,
        status,
        List.copyOf(missing),
        current,
        previous,
        status == CoverageStatus.AVAILABLE
            ? measurementExceptions(jdbc, from, to, dimensionPresent)
            : List.of(),
        status == CoverageStatus.AVAILABLE ? measurementTimeline(jdbc, from, to) : List.of(),
        status == CoverageStatus.AVAILABLE
            ? measurementSensors(jdbc, from, to, dimensionPresent)
            : List.of(),
        null);
  }

  private ReadingsDashboard.Summary measurementSummary(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForObject(
        """
            WITH ranked AS (
                SELECT SensorId, TimeSpan, LocalTimeSpan, COALESCE(ReadingsCount, 0) AS ReadingsCount,
                       ROW_NUMBER() OVER (PARTITION BY SensorId, TimeSpan ORDER BY ModifiedAt DESC, OperationId DESC) AS SourceRowNumber
                FROM dwh.factReadingsMeasurement
                WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            ), deduped AS (
                SELECT SensorId, TimeSpan, LocalTimeSpan, ReadingsCount
                FROM ranked WHERE SourceRowNumber = 1
            ), sequenced AS (
                SELECT SensorId, TimeSpan, LocalTimeSpan, ReadingsCount,
                       MAX(CASE WHEN ReadingsCount > 0 THEN TimeSpan END) OVER (PARTITION BY SensorId ORDER BY TimeSpan ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS LastReadingAt,
                       ROW_NUMBER() OVER (PARTITION BY SensorId ORDER BY TimeSpan DESC) AS LatestRow
                FROM deduped
            )
            SELECT COUNT_BIG(*) AS SensorsObserved,
                   COALESCE(SUM(CASE WHEN ReadingsCount > 0 THEN 1 ELSE 0 END), 0) AS HealthySensors,
                   COALESCE(SUM(CASE WHEN ReadingsCount <= 0 THEN 1 ELSE 0 END), 0) AS DisconnectedSensors,
                   CAST(0 AS bigint) AS LateSensors,
                   AVG(CASE WHEN ReadingsCount <= 0 AND LastReadingAt IS NOT NULL THEN CAST(DATEDIFF(MINUTE, LastReadingAt, TimeSpan) AS float) END) AS AvgMinutesWithoutReadings,
                   MAX(CASE WHEN ReadingsCount <= 0 AND LastReadingAt IS NOT NULL THEN CAST(DATEDIFF(MINUTE, LastReadingAt, TimeSpan) AS float) END) AS MaxMinutesWithoutReadings,
                   MAX(TimeSpan) AS LatestAuditAt,
                   MAX(LastReadingAt) AS LatestReadingAt
            FROM sequenced WHERE LatestRow = 1
            """,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.Summary(
                resultSet.getLong("SensorsObserved"),
                resultSet.getLong("HealthySensors"),
                resultSet.getLong("DisconnectedSensors"),
                resultSet.getLong("LateSensors"),
                nullableDouble(resultSet, "AvgMinutesWithoutReadings"),
                nullableDouble(resultSet, "MaxMinutesWithoutReadings"),
                instant(resultSet.getTimestamp("LatestAuditAt")),
                instant(resultSet.getTimestamp("LatestReadingAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorException> measurementExceptions(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, boolean dimensionPresent) {
    String dimensionCte =
        dimensionPresent
            ? ", dimensions AS (SELECT SensorId, MAX(location_name) AS LocationName, MAX(device_name) AS DeviceName, MAX(sensor_name) AS SensorName FROM dwh.dimSidonProdDimensions GROUP BY SensorId)"
            : "";
    String names =
        dimensionPresent
            ? "COALESCE(d.LocationName, N'Sin ubicacion') AS LocationName, COALESCE(d.DeviceName, N'Sin dispositivo') AS DeviceName, COALESCE(d.SensorName, CONVERT(varchar(36), s.SensorId)) AS SensorName"
            : "N'Dimension no disponible' AS LocationName, N'Dimension no disponible' AS DeviceName, CONVERT(varchar(36), s.SensorId) AS SensorName";
    String join = dimensionPresent ? "LEFT JOIN dimensions AS d ON d.SensorId = s.SensorId" : "";
    String sql =
        """
            WITH ranked AS (
                SELECT SensorId, TimeSpan, COALESCE(ReadingsCount, 0) AS ReadingsCount,
                       ROW_NUMBER() OVER (PARTITION BY SensorId, TimeSpan ORDER BY ModifiedAt DESC, OperationId DESC) AS SourceRowNumber
                FROM dwh.factReadingsMeasurement
                WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            ), deduped AS (
                SELECT SensorId, TimeSpan, ReadingsCount FROM ranked WHERE SourceRowNumber = 1
            ), sequenced AS (
                SELECT SensorId, TimeSpan, ReadingsCount,
                       MAX(CASE WHEN ReadingsCount > 0 THEN TimeSpan END) OVER (PARTITION BY SensorId ORDER BY TimeSpan ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS LastReadingAt,
                       ROW_NUMBER() OVER (PARTITION BY SensorId ORDER BY TimeSpan DESC) AS LatestRow
                FROM deduped
            )%s
            SELECT TOP (50) CONVERT(varchar(36), s.SensorId) AS SensorId,
                   %s,
                   CAST(1 AS int) AS IsConnectionLost,
                   CAST(0 AS int) AS HasLateReadings,
                   CASE WHEN s.LastReadingAt IS NULL THEN NULL ELSE CAST(DATEDIFF(MINUTE, s.LastReadingAt, s.TimeSpan) AS float) END AS MinutesWithoutReadings,
                   s.LastReadingAt
            FROM sequenced AS s %s
            WHERE s.LatestRow = 1 AND s.ReadingsCount <= 0
            ORDER BY MinutesWithoutReadings DESC, s.SensorId
            """
            .formatted(dimensionCte, names, join);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorException(
                resultSet.getString("SensorId"),
                resultSet.getString("LocationName"),
                resultSet.getString("DeviceName"),
                resultSet.getString("SensorName"),
                resultSet.getInt("IsConnectionLost") == 1,
                false,
                nullableDouble(resultSet, "MinutesWithoutReadings"),
                instant(resultSet.getTimestamp("LastReadingAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorTimeline> measurementTimeline(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
            WITH ranked AS (
                SELECT SensorId, TimeSpan, COALESCE(ReadingsCount, 0) AS ReadingsCount,
                       ROW_NUMBER() OVER (PARTITION BY SensorId, TimeSpan ORDER BY ModifiedAt DESC, OperationId DESC) AS SourceRowNumber
                FROM dwh.factReadingsMeasurement
                WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            ), deduped AS (
                SELECT SensorId, TimeSpan, ReadingsCount
                FROM ranked WHERE SourceRowNumber = 1
            )
            SELECT CONVERT(varchar(36), SensorId) AS SensorId, TimeSpan, ReadingsCount,
                   CAST(0 AS int) AS HasLateReadings,
                   CAST(CASE WHEN ReadingsCount <= 0 THEN 1 ELSE 0 END AS int) AS IsConnectionLost,
                   CASE WHEN ReadingsCount <= 0 THEN CAST(DATEDIFF(MINUTE, MAX(CASE WHEN ReadingsCount > 0 THEN TimeSpan END) OVER (PARTITION BY SensorId ORDER BY TimeSpan ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), TimeSpan) AS float) END AS MinutesWithoutReadings
            FROM deduped ORDER BY SensorId, TimeSpan
            """,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorTimeline(
                resultSet.getString("SensorId"),
                instant(resultSet.getTimestamp("TimeSpan")),
                resultSet.getLong("ReadingsCount"),
                false,
                resultSet.getInt("IsConnectionLost") == 1,
                nullableDouble(resultSet, "MinutesWithoutReadings")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  /** Keep the aggregate tie-breaker in SQL; the client renders this order directly. */
  private List<ReadingsDashboard.SensorAggregate> measurementSensors(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, boolean dimensionPresent) {
    String dimensionCte =
        dimensionPresent
            ? ", dimensions AS (SELECT SensorId, MAX(location_name) AS LocationName, MAX(device_name) AS DeviceName, MAX(sensor_name) AS SensorName FROM dwh.dimSidonProdDimensions GROUP BY SensorId)"
            : "";
    String names =
        dimensionPresent
            ? "COALESCE(d.LocationName, N'Sin ubicacion') AS LocationName, COALESCE(d.DeviceName, N'Sin dispositivo') AS DeviceName, COALESCE(d.SensorName, CONVERT(varchar(36), s.SensorId)) AS SensorName"
            : "N'Dimension no disponible' AS LocationName, N'Dimension no disponible' AS DeviceName, CONVERT(varchar(36), s.SensorId) AS SensorName";
    String join = dimensionPresent ? "LEFT JOIN dimensions AS d ON d.SensorId = s.SensorId" : "";
    String group =
        dimensionPresent
            ? "s.SensorId, d.LocationName, d.DeviceName, d.SensorName"
            : "s.SensorId";
    String sql =
        """
            WITH ranked AS (
                SELECT SensorId, TimeSpan, COALESCE(ReadingsCount, 0) AS ReadingsCount,
                       ROW_NUMBER() OVER (PARTITION BY SensorId, TimeSpan ORDER BY ModifiedAt DESC, OperationId DESC) AS SourceRowNumber
                FROM dwh.factReadingsMeasurement
                WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            ), deduped AS (
                SELECT SensorId, TimeSpan, ReadingsCount FROM ranked WHERE SourceRowNumber = 1
            )%s
            SELECT CONVERT(varchar(36), s.SensorId) AS SensorId,
                   %s,
                   COUNT_BIG(*) AS ObservedIntervals,
                   COALESCE(SUM(CASE WHEN s.ReadingsCount <= 0 THEN 1 ELSE 0 END), 0) AS LostIntervals
            FROM deduped AS s %s
            GROUP BY %s
            ORDER BY LostIntervals DESC, COALESCE(SUM(s.ReadingsCount), 0) DESC
            """
            .formatted(dimensionCte, names, join, group);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorAggregate(
                resultSet.getString("SensorId"),
                resultSet.getString("LocationName"),
                resultSet.getString("DeviceName"),
                resultSet.getString("SensorName"),
                resultSet.getLong("ObservedIntervals"),
                resultSet.getLong("LostIntervals")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private ReadingsDashboard.Summary summary(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForObject(
        """
            WITH latest AS (
                SELECT SensorId, TimeSpan, HasLateReadings, IsConnectionLost, LastReadingAt, MinutesWithoutReadings,
                       ROW_NUMBER() OVER (PARTITION BY SensorId ORDER BY TimeSpan DESC) AS rn
                FROM observability.factRedingsAudits
                WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            )
            SELECT COUNT_BIG(*) AS SensorsObserved,
                   COALESCE(SUM(CASE WHEN IsConnectionLost = 0 THEN 1 ELSE 0 END), 0) AS HealthySensors,
                   COALESCE(SUM(CASE WHEN IsConnectionLost = 1 THEN 1 ELSE 0 END), 0) AS DisconnectedSensors,
                   COALESCE(SUM(CASE WHEN HasLateReadings = 1 THEN 1 ELSE 0 END), 0) AS LateSensors,
                   AVG(CAST(MinutesWithoutReadings AS float)) AS AvgMinutesWithoutReadings,
                   MAX(CAST(MinutesWithoutReadings AS float)) AS MaxMinutesWithoutReadings,
                   MAX(TimeSpan) AS LatestAuditAt,
                   MAX(LastReadingAt) AS LatestReadingAt
            FROM latest WHERE rn = 1
            """,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.Summary(
                resultSet.getLong("SensorsObserved"),
                resultSet.getLong("HealthySensors"),
                resultSet.getLong("DisconnectedSensors"),
                resultSet.getLong("LateSensors"),
                nullableDouble(resultSet, "AvgMinutesWithoutReadings"),
                nullableDouble(resultSet, "MaxMinutesWithoutReadings"),
                instant(resultSet.getTimestamp("LatestAuditAt")),
                instant(resultSet.getTimestamp("LatestReadingAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorException> exceptions(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, boolean dimensionPresent) {
    String selectNames =
        dimensionPresent
            ? "COALESCE(d.location_name, N'Sin ubicacion') AS LocationName, COALESCE(d.device_name, N'Sin dispositivo') AS DeviceName, COALESCE(d.sensor_name, N'Sin nombre') AS SensorName"
            : "N'Dimension no disponible' AS LocationName, N'Dimension no disponible' AS DeviceName, N'Dimension no disponible' AS SensorName";
    String join =
        dimensionPresent
            ? "LEFT JOIN dwh.dimSidonProdDimensions AS d ON d.SensorId = latest.SensorId"
            : "";
    String sql =
        """
            WITH latest AS (
                SELECT SensorId, TimeSpan, HasLateReadings, IsConnectionLost, LastReadingAt, MinutesWithoutReadings,
                       ROW_NUMBER() OVER (PARTITION BY SensorId ORDER BY TimeSpan DESC) AS rn
                FROM observability.factRedingsAudits
                WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            )
            SELECT TOP (50) CONVERT(varchar(36), latest.SensorId) AS SensorId,
                   %s,
                   CAST(latest.IsConnectionLost AS int) AS IsConnectionLost,
                   CAST(latest.HasLateReadings AS int) AS HasLateReadings,
                   CAST(latest.MinutesWithoutReadings AS float) AS MinutesWithoutReadings,
                   latest.LastReadingAt
            FROM latest %s
            WHERE latest.rn = 1 AND (latest.IsConnectionLost = 1 OR latest.HasLateReadings = 1)
            ORDER BY latest.IsConnectionLost DESC, latest.MinutesWithoutReadings DESC, latest.SensorId
            """
            .formatted(selectNames, join);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorException(
                resultSet.getString("SensorId"),
                resultSet.getString("LocationName"),
                resultSet.getString("DeviceName"),
                resultSet.getString("SensorName"),
                resultSet.getInt("IsConnectionLost") == 1,
                resultSet.getInt("HasLateReadings") == 1,
                nullableDouble(resultSet, "MinutesWithoutReadings"),
                instant(resultSet.getTimestamp("LastReadingAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<ReadingsDashboard.SensorTimeline> timeline(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
            SELECT CONVERT(varchar(36), SensorId) AS SensorId,
                   TimeSpan, ReadingsCount,
                   CAST(HasLateReadings AS int) AS HasLateReadings,
                   CAST(IsConnectionLost AS int) AS IsConnectionLost,
                   CAST(MinutesWithoutReadings AS float) AS MinutesWithoutReadings
            FROM observability.factRedingsAudits
            WHERE TimeSpan >= ? AND TimeSpan < DATEADD(DAY, 1, ?)
            ORDER BY SensorId, TimeSpan
            """,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorTimeline(
                resultSet.getString("SensorId"),
                instant(resultSet.getTimestamp("TimeSpan")),
                resultSet.getLong("ReadingsCount"),
                resultSet.getInt("HasLateReadings") == 1,
                resultSet.getInt("IsConnectionLost") == 1,
                nullableDouble(resultSet, "MinutesWithoutReadings")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  /** Keep aggregate tie-breakers in SQL; the client renders this order directly. */
  private List<ReadingsDashboard.SensorAggregate> sensors(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, boolean dimensionPresent) {
    String names =
        dimensionPresent
            ? "COALESCE(d.location_name, N'Sin ubicacion') AS LocationName, COALESCE(d.device_name, N'Sin dispositivo') AS DeviceName, COALESCE(d.sensor_name, CONVERT(varchar(36), a.SensorId)) AS SensorName"
            : "N'Dimension no disponible' AS LocationName, N'Dimension no disponible' AS DeviceName, CONVERT(varchar(36), a.SensorId) AS SensorName";
    String join =
        dimensionPresent
            ? "LEFT JOIN dwh.dimSidonProdDimensions AS d ON d.SensorId = a.SensorId"
            : "";
    String group =
        dimensionPresent
            ? "a.SensorId, d.location_name, d.device_name, d.sensor_name"
            : "a.SensorId";
    String sql =
        """
            SELECT CONVERT(varchar(36), a.SensorId) AS SensorId,
                   %s,
                   COUNT_BIG(*) AS ObservedIntervals,
                   COALESCE(SUM(CASE WHEN a.IsConnectionLost = 1 THEN 1 ELSE 0 END), 0) AS LostIntervals
            FROM observability.factRedingsAudits AS a
            %s
            WHERE a.TimeSpan >= ? AND a.TimeSpan < DATEADD(DAY, 1, ?)
            GROUP BY %s
            ORDER BY LostIntervals DESC,
                     COALESCE(SUM(CASE WHEN a.HasLateReadings = 1 THEN 1 ELSE 0 END), 0) DESC,
                     COALESCE(SUM(a.ReadingsCount), 0) DESC
            """
            .formatted(names, join, group);
    return jdbc.query(
        sql,
        (resultSet, rowNumber) ->
            new ReadingsDashboard.SensorAggregate(
                resultSet.getString("SensorId"),
                resultSet.getString("LocationName"),
                resultSet.getString("DeviceName"),
                resultSet.getString("SensorName"),
                resultSet.getLong("ObservedIntervals"),
                resultSet.getLong("LostIntervals")),
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

  private boolean viewExists(JdbcTemplate jdbc, String objectName) {
    Integer value =
        jdbc.queryForObject(
            "SELECT CASE WHEN OBJECT_ID(?, N'V') IS NULL THEN 0 ELSE 1 END",
            Integer.class,
            objectName);
    return value != null && value == 1;
  }

  private boolean hasColumn(JdbcTemplate jdbc, String objectName, String columnName) {
    Integer value =
        jdbc.queryForObject(
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID(?) AND name = ?) THEN 1 ELSE 0 END",
            Integer.class,
            objectName,
            columnName);
    return value != null && value == 1;
  }

  private String optionalColumn(
      JdbcTemplate jdbc, String objectName, String columnName, String expression, String sqlType) {
    return hasColumn(jdbc, objectName, columnName)
        ? expression + " AS " + columnName
        : "CAST(NULL AS " + sqlType + ") AS " + columnName;
  }

  private String optionalHealthColumn(
      JdbcTemplate jdbc, String objectName, String columnName, String fallback, String prefix) {
    return hasColumn(jdbc, objectName, columnName) ? prefix + columnName : fallback;
  }

  private int hasSelect(JdbcTemplate jdbc, String objectName) {
    Integer value =
        jdbc.queryForObject(
            "SELECT HAS_PERMS_BY_NAME(?, N'OBJECT', N'SELECT')", Integer.class, objectName);
    return value == null ? 0 : value;
  }

  private Double nullableDouble(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    double value = resultSet.getDouble(column);
    return resultSet.wasNull() ? null : value;
  }

  private Long nullableLong(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private Boolean nullableBoolean(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    boolean value = resultSet.getBoolean(column);
    return resultSet.wasNull() ? null : value;
  }

  private java.time.Instant instant(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
  }

  private String localTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().format(LOCAL_TIME_FORMAT);
  }

  private ReadingsDashboard.TenantResult unavailable(String id, String name, String errorCode) {
    return new ReadingsDashboard.TenantResult(
        id,
        name,
        CoverageStatus.UNAVAILABLE,
        List.of(),
        ReadingsDashboard.Summary.empty(),
        ReadingsDashboard.Summary.empty(),
        List.of(),
        List.of(),
        List.of(),
        errorCode);
  }

  private enum ServingSource {
    HOURLY(
        "serving.vwSenseSensorCommunicationHealthHourly",
        "TimeSpan",
        "LocalTimeSpan",
        "TimeSpan",
        "TimeSpan"),
    DAILY(
        "serving.vwSenseSensorCommunicationHealthDaily",
        "CONVERT(datetime2, LocalDate)",
        "CONVERT(datetime2, LocalDate)",
        "LocalDate",
        "LocalDate"),
    WEEKLY(
        "serving.vwSenseSensorCommunicationHealthWeekly",
        "CONVERT(datetime2, WeekStartDate)",
        "CONVERT(datetime2, WeekStartDate)",
        "WeekEndDate",
        "WeekStartDate");

    private final String view;
    private final String periodColumn;
    private final String localPeriodColumn;
    private final String startFilterColumn;
    private final String endFilterColumn;

    ServingSource(
        String view,
        String periodColumn,
        String localPeriodColumn,
        String startFilterColumn,
        String endFilterColumn) {
      this.view = view;
      this.periodColumn = periodColumn;
      this.localPeriodColumn = localPeriodColumn;
      this.startFilterColumn = startFilterColumn;
      this.endFilterColumn = endFilterColumn;
    }

    private static ServingSource forPeriod(LocalDate from, LocalDate to) {
      long days = ChronoUnit.DAYS.between(from, to);
      if (days < 8) return HOURLY;
      if (days < 42) return DAILY;
      return WEEKLY;
    }

    private String view() {
      return view;
    }

    private String periodColumn() {
      return periodColumn;
    }

    private String localPeriodColumn() {
      return localPeriodColumn;
    }

    private String qualifiedLocalPeriodColumn() {
      return switch (this) {
        case HOURLY -> "health.LocalTimeSpan";
        case DAILY -> "CONVERT(datetime2, health.LocalDate)";
        case WEEKLY -> "CONVERT(datetime2, health.WeekStartDate)";
      };
    }

    private String startFilterColumn() {
      return startFilterColumn;
    }

    private String endFilterColumn() {
      return endFilterColumn;
    }
  }
}
