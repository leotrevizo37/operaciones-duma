package com.duma.readings.domain;

import com.duma.core.domain.CoverageStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class ReadingsDashboard {
  private ReadingsDashboard() {}

  public record Response(
      Instant generatedAt, LocalDate from, LocalDate to, List<TenantResult> tenants) {}

  public record TenantResult(
      String tenantId,
      String tenantName,
      CoverageStatus coverageStatus,
      List<String> missingSources,
      Summary current,
      Summary previous,
      List<SensorException> exceptions,
      List<SensorTimeline> timeline,
      List<SensorAggregate> sensors,
      String errorCode,
      List<Diagnostics> diagnostics) {
    public TenantResult(
        String tenantId, String tenantName, CoverageStatus coverageStatus,
        List<String> missingSources, Summary current, Summary previous,
        List<SensorException> exceptions,
        List<SensorTimeline> timeline, List<SensorAggregate> sensors, String errorCode) {
      this(tenantId, tenantName, coverageStatus, missingSources, current, previous,
          exceptions, timeline, sensors, errorCode, List.of());
    }
  }

  public record Diagnostics(
      String sensorId, String localPeriodStart, String source, List<JsonNode> hours) {}

  public record Summary(
      long sensorsObserved,
      long healthySensors,
      long disconnectedSensors,
      long lateSensors,
      Double avgMinutesWithoutReadings,
      Double maxMinutesWithoutReadings,
      Instant latestAuditAt,
      Instant latestReadingAt) {
    public static Summary empty() {
      return new Summary(0, 0, 0, 0, null, null, null, null);
    }
  }

  public record SensorException(
      String sensorId,
      String locationName,
      String deviceName,
      String sensorName,
      boolean disconnected,
      boolean late,
      Double minutesWithoutReadings,
      Instant lastReadingAt) {}

  public record SensorTimeline(
      String sensorId,
      Instant timeSpan,
      String localTimeSpan,
      long readingsCount,
      boolean late,
      boolean disconnected,
      Double minutesWithoutReadings,
      String devEui,
      Double dataCoveragePercentage,
      Long receivedUplinkCount,
      Double expectedUplinkCount,
      Double chirpStackReceiptCoveragePercentage,
      Double averageRssi,
      Double averageSnr,
      Long linkErrorCount,
      Long gatewayReceivedPacketCount,
      Long gatewayTransmittedPacketCount,
      Long gatewayTxOkCount,
      Long gatewayTxErrorCount,
      Double batteryLevel,
      Boolean externalPowerSource,
      String batteryLevelSource,
      Instant batteryObservedAt,
      String communicationStatus) {
    public SensorTimeline(
        String sensorId, Instant timeSpan, long readingsCount, boolean late,
        boolean disconnected,
        Double minutesWithoutReadings) {
      this(
          sensorId, timeSpan, null, readingsCount, late, disconnected,
          minutesWithoutReadings, null, null, null, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null);
    }
  }

  /** The client renders sensors in this order without re-sorting. */
  public record SensorAggregate(
      String sensorId,
      String locationName,
      String deviceName,
      String sensorName,
      long observedIntervals,
      long lostIntervals,
      String modelName,
      String physicalIdentifier) {
    public SensorAggregate(
        String sensorId, String locationName, String deviceName, String sensorName,
        long observedIntervals, long lostIntervals) {
      this(
          sensorId, locationName, deviceName, sensorName, observedIntervals, lostIntervals, null, null);
    }
  }
}
