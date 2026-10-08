package com.duma.readings.config;

import com.duma.core.domain.CoverageStatus;
import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.config.ModuleProperties;
import com.duma.readings.data.ReadingsRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadingsRepositoryIT {

  private static final String SENSOR_A = "11111111-1111-1111-1111-111111111111";
  private static final String SENSOR_B = "22222222-2222-2222-2222-222222222222";

  private static final String SENSOR_C = "33333333-3333-3333-3333-333333333333";

  private static final String SENSOR_D = "44444444-4444-4444-4444-444444444444";

  @Container
  private static final MSSQLServerContainer<?> SQL_SERVER =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

  private TenantDataSourceRegistry registry;
  private ReadingsRepository repository;
  private JdbcTemplate jdbc;

  @BeforeAll
  void connect() {
    ModuleProperties properties = properties();
    registry = new TenantDataSourceRegistry(properties);
    repository = new ReadingsRepository(properties, registry);
    jdbc = registry.jdbc("tenant-test");
    jdbc.execute("IF SCHEMA_ID(N'observability') IS NULL EXEC(N'CREATE SCHEMA observability')");
    jdbc.execute("IF SCHEMA_ID(N'dwh') IS NULL EXEC(N'CREATE SCHEMA dwh')");
  }

  @BeforeEach
  void resetTables() {
    jdbc.execute(
        "IF OBJECT_ID(N'observability.factRedingsAudits',N'U') IS NOT NULL DROP TABLE observability.factRedingsAudits");
    jdbc.execute(
        "IF OBJECT_ID(N'dwh.dimSidonProdDimensions',N'U') IS NOT NULL DROP TABLE dwh.dimSidonProdDimensions");
    jdbc.execute(
        "IF OBJECT_ID(N'dwh.factReadingsMeasurement',N'U') IS NOT NULL DROP TABLE dwh.factReadingsMeasurement");
    jdbc.execute(
        """
        CREATE TABLE observability.factRedingsAudits(
            SensorId uniqueidentifier NOT NULL,
            TimeSpan datetime2(0) NOT NULL,
            LocalTimeSpan datetime2(0) NOT NULL,
            ReadingsCount bigint NOT NULL,
            HasLateReadings bit NOT NULL,
            IsConnectionLost bit NOT NULL,
            LastReadingAt datetime2(0) NULL,
            ConnectionLostAt datetime2(0) NULL,
            MinutesWithoutReadings int NULL
        )
        """);
    jdbc.execute(
        """
        CREATE TABLE dwh.factReadingsMeasurement(
            SensorId uniqueidentifier NOT NULL,
            TimeSpan datetime2(0) NOT NULL,
            LocalTimeSpan datetime2(0) NOT NULL,
            ReadingsCount bigint NULL,
            ModifiedAt datetime2(0) NOT NULL,
            OperationId uniqueidentifier NOT NULL
        )
        """);
    jdbc.execute(
        """
        CREATE TABLE dwh.dimSidonProdDimensions(
            SensorId uniqueidentifier NOT NULL PRIMARY KEY,
            location_name nvarchar(255) NULL,
            device_name nvarchar(255) NULL,
            sensor_name nvarchar(255) NULL
        )
        """);
  }

  @AfterAll
  void close() {
    registry.close();
  }

  @Test
  void returnsLatestSensorStateAndNamedExceptionFromSqlServer() {
    jdbc.update(
        """
        INSERT INTO dwh.dimSidonProdDimensions VALUES
            (CONVERT(uniqueidentifier,?),N'Sucursal Norte',N'Cuarto frio',N'Temperatura retorno'),
            (CONVERT(uniqueidentifier,?),N'Sucursal Sur',N'HVAC',N'Temperatura ambiente')
        """,
        SENSOR_A,
        SENSOR_B);
    jdbc.update(
        """
        INSERT INTO observability.factRedingsAudits VALUES
            (CONVERT(uniqueidentifier,?),'2026-01-10T12:00:00','2026-01-10T06:00:00',0,1,1,'2026-01-10T10:50:00','2026-01-10T11:00:00',70),
            (CONVERT(uniqueidentifier,?),'2026-01-10T12:00:00','2026-01-10T06:00:00',6,0,0,'2026-01-10T11:59:00',NULL,0)
        """,
        SENSOR_A,
        SENSOR_B);

    var result =
        repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.AVAILABLE);
    assertThat(result.missingSources()).isEmpty();
    assertThat(result.current().sensorsObserved()).isEqualTo(2);
    assertThat(result.current().healthySensors()).isEqualTo(1);
    assertThat(result.current().disconnectedSensors()).isEqualTo(1);
    assertThat(result.current().lateSensors()).isEqualTo(1);
    assertThat(result.exceptions())
        .singleElement()
        .satisfies(
            exception -> {
              assertThat(exception.sensorId()).isEqualTo(SENSOR_A);
              assertThat(exception.locationName()).isEqualTo("Sucursal Norte");
              assertThat(exception.deviceName()).isEqualTo("Cuarto frio");
              assertThat(exception.sensorName()).isEqualTo("Temperatura retorno");
              assertThat(exception.disconnected()).isTrue();
              assertThat(exception.late()).isTrue();
            });
  }

  @Test
  void usesMeasurementsWhenAuditFactIsUnavailable() {
    jdbc.execute("DROP TABLE observability.factRedingsAudits");
    jdbc.update(
        """
        INSERT INTO dwh.dimSidonProdDimensions VALUES
            (CONVERT(uniqueidentifier,?),N'Sucursal Norte',N'Cuarto frio',N'Temperatura retorno'),
            (CONVERT(uniqueidentifier,?),N'Sucursal Sur',N'HVAC',N'Temperatura ambiente')
        """,
        SENSOR_A,
        SENSOR_B);
    jdbc.update(
        """
        INSERT INTO dwh.factReadingsMeasurement VALUES
            (CONVERT(uniqueidentifier,?),'2026-01-10T10:00:00','2026-01-10T04:00:00',6,'2026-01-10T10:05:00',NEWID()),
            (CONVERT(uniqueidentifier,?),'2026-01-10T11:00:00','2026-01-10T05:00:00',0,'2026-01-10T11:05:00',NEWID()),
            (CONVERT(uniqueidentifier,?),'2026-01-10T11:00:00','2026-01-10T05:00:00',5,'2026-01-10T11:05:00',NEWID())
        """,
        SENSOR_A,
        SENSOR_A,
        SENSOR_B);

    var result =
        repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.AVAILABLE);
    assertThat(result.missingSources()).containsExactly("observability.factRedingsAudits");
    assertThat(result.current().sensorsObserved()).isEqualTo(2);
    assertThat(result.current().healthySensors()).isEqualTo(1);
    assertThat(result.current().disconnectedSensors()).isEqualTo(1);
    assertThat(result.current().lateSensors()).isZero();
    assertThat(result.exceptions()).singleElement().extracting("sensorId").isEqualTo(SENSOR_A);
    assertThat(result.timeline()).hasSize(3);
    assertThat(result.sensors()).hasSize(2);
  }

  @Test
  void servesPeriodBatteryAndKeepsMqttCountsSeparateFromGatewayReceptions() {
    jdbc.execute("IF SCHEMA_ID(N'serving') IS NULL EXEC(N'CREATE SCHEMA serving')");
    List<String> views = List.of("serving.vwSenseSensors", "serving.vwSenseSensorCommunicationHealthHourly",
        "serving.vwSenseSensorCommunicationHealthDaily", "serving.vwSenseSensorCommunicationHealthWeekly");
    List<String> tables = List.of("observability.factChirpStackDeviceEventMetrics",
        "observability.factChirpStackDeviceGatewayMetrics", "observability.factChirpStackDeviceMetrics",
        "observability.factChirpStackGatewayMetrics");
    try {
      jdbc.execute("CREATE VIEW serving.vwSenseSensors AS SELECT CONVERT(uniqueidentifier,'" + SENSOR_A
          + "') SensorId, N'L' LocationName, N'D' DeviceName, N'S' SensorName, N'M' ModelName");
      jdbc.execute("CREATE VIEW serving.vwSenseSensorCommunicationHealthHourly AS SELECT CONVERT(uniqueidentifier,'"
          + SENSOR_A + "') SensorId, CONVERT(datetime2,'2026-01-10T12:00:00') TimeSpan, "
          + "CONVERT(datetime2,'2026-01-10T06:00:00') LocalTimeSpan, CONVERT(bigint,6) ReadingsCount, "
          + "N'0000000000000001' DevEui, CONVERT(float,80) BatteryLevel");
      jdbc.execute("CREATE VIEW serving.vwSenseSensorCommunicationHealthDaily AS SELECT SensorId, "
          + "CONVERT(date,LocalTimeSpan) LocalDate, ReadingsCount, DevEui, CONVERT(float,73) BatteryLevel "
          + "FROM serving.vwSenseSensorCommunicationHealthHourly");
      jdbc.execute("CREATE VIEW serving.vwSenseSensorCommunicationHealthWeekly AS SELECT SensorId, "
          + "CONVERT(date,'2026-01-05') WeekStartDate, CONVERT(date,'2026-01-11') WeekEndDate, "
          + "ReadingsCount, DevEui, CONVERT(float,71) BatteryLevel FROM serving.vwSenseSensorCommunicationHealthHourly");
      jdbc.execute("SELECT DevEui, TimeSpan, CONVERT(bigint,2) CapturedUplinkCount, "
          + "CONVERT(bigint,1) MultiGatewayUplinkCount, CAST(N'{\"INFO\":1}' AS nvarchar(max)) EventLogCountsJson "
          + "INTO observability.factChirpStackDeviceEventMetrics FROM serving.vwSenseSensorCommunicationHealthHourly");
      jdbc.execute("SELECT DevEui, TimeSpan, CONVERT(uniqueidentifier,'" + SENSOR_A + "') TenantId, "
          + "N'0000000000000002' GatewayId, CONVERT(bigint,2) GatewayReceptionCount, "
          + "CAST(N'[{\"spreadingFactor\":10,\"snrCount\":2,\"snrSum\":\"-30.000000\"}]' AS nvarchar(max)) RadioReceptionStatsJson "
          + "INTO observability.factChirpStackDeviceGatewayMetrics FROM serving.vwSenseSensorCommunicationHealthHourly");
      jdbc.execute("INSERT INTO observability.factChirpStackDeviceGatewayMetrics "
          + "SELECT DevEui, TimeSpan, TenantId, N'0000000000000003', 1, NULL FROM observability.factChirpStackDeviceGatewayMetrics");
      jdbc.execute("SELECT DevEui, TimeSpan, CONVERT(bigint,1) LinkErrorCount, "
          + "CAST(N'{\"MIC\":1}' AS nvarchar(max)) LinkErrorCountsJson INTO observability.factChirpStackDeviceMetrics "
          + "FROM serving.vwSenseSensorCommunicationHealthHourly");
      jdbc.execute("SELECT GatewayId, TimeSpan, TenantId, CAST(N'{\"OK\":2,\"ERROR\":1}' AS nvarchar(max)) TxPacketStatusCountsJson "
          + "INTO observability.factChirpStackGatewayMetrics FROM observability.factChirpStackDeviceGatewayMetrics");

      var daily = repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), true);
      assertThat(daily.coverageStatus()).isEqualTo(CoverageStatus.AVAILABLE);
      assertThat(daily.missingSources()).isEmpty();
      assertThat(daily.timeline()).singleElement().satisfies(row -> {
        assertThat(row.batteryLevel()).isEqualTo(73);
        assertThat(row.batteryObservedAt()).isNull();
        assertThat(row.externalPowerSource()).isNull();
      });
      assertThat(daily.diagnostics()).hasSize(4);
      var events = daily.diagnostics().stream().filter(row -> row.source().equals(tables.get(0))).findFirst().orElseThrow();
      assertThat(events.localPeriodStart()).isEqualTo("2026-01-10T00:00:00");
      assertThat(events.hours()).singleElement().satisfies(hour -> {
        assertThat(hour.get("capturedUplinkCount").asLong()).isEqualTo(2);
        assertThat(hour.get("timeSpan").asText()).isEqualTo("2026-01-10T12:00:00Z");
        assertThat(hour.get("eventLogCounts").get("INFO").asInt()).isEqualTo(1);
      });
      var receptions = daily.diagnostics().stream().filter(row -> row.source().equals(tables.get(1))).findFirst().orElseThrow();
      assertThat(receptions.hours()).hasSize(2);
      assertThat(receptions.hours().stream().mapToLong(hour -> hour.get("gatewayReceptionCount").asLong()).sum()).isEqualTo(3);
      var weekly = repository.load("tenant-test", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 3, 1), true);
      assertThat(weekly.timeline()).singleElement().extracting("batteryLevel").isEqualTo(71.0);
      assertThat(weekly.diagnostics()).allSatisfy(row -> assertThat(row.localPeriodStart()).isEqualTo("2026-01-05T00:00:00"));
      assertThat(repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).diagnostics()).isEmpty();
      jdbc.execute("DROP TABLE observability.factChirpStackDeviceMetrics");
      var partial = repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), true);
      assertThat(partial.coverageStatus()).isEqualTo(CoverageStatus.AVAILABLE);
      assertThat(partial.missingSources()).contains(tables.get(2));
      jdbc.execute("UPDATE observability.factChirpStackDeviceEventMetrics SET EventLogCountsJson = N'broken'");
      assertThat(repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), true).errorCode())
          .isEqualTo("TENANT_QUERY_FAILED");
      jdbc.execute("UPDATE observability.factChirpStackDeviceEventMetrics SET EventLogCountsJson = N'{}'");
      jdbc.execute("UPDATE observability.factChirpStackDeviceGatewayMetrics SET RadioReceptionStatsJson = N'[null]'");
      assertThat(repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), true).errorCode())
          .isEqualTo("TENANT_QUERY_FAILED");
    } finally {
      for (int index = views.size() - 1; index >= 0; index--) jdbc.execute("DROP VIEW IF EXISTS " + views.get(index));
      for (String table : tables) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }
  }

  @Test
  void sensorRowsArriveInTheOrderTheHeatMapPaints() {
    jdbc.update(
        """
        INSERT INTO observability.factRedingsAudits VALUES
            (CONVERT(uniqueidentifier,?),'2026-01-10T12:00:00','2026-01-10T06:00:00',0,0,1,NULL,'2026-01-10T11:00:00',70),
            (CONVERT(uniqueidentifier,?),'2026-01-10T12:00:00','2026-01-10T06:00:00',1,1,0,'2026-01-10T11:59:00',NULL,0),
            (CONVERT(uniqueidentifier,?),'2026-01-10T12:00:00','2026-01-10T06:00:00',9,0,0,'2026-01-10T11:59:00',NULL,0),
            (CONVERT(uniqueidentifier,?),'2026-01-10T12:00:00','2026-01-10T06:00:00',6,0,0,'2026-01-10T11:59:00',NULL,0)
        """,
        SENSOR_A,
        SENSOR_D,
        SENSOR_C,
        SENSOR_B);

    var result =
        repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.sensors())
        .extracting("sensorId")
        .containsExactly(SENSOR_A, SENSOR_D, SENSOR_C, SENSOR_B);
  }

  @Test
  void measurementSensorRowsArriveInTheOrderTheHeatMapPaints() {
    jdbc.execute("DROP TABLE observability.factRedingsAudits");
    jdbc.update(
        """
        INSERT INTO dwh.factReadingsMeasurement VALUES
            (CONVERT(uniqueidentifier,?),'2026-01-10T11:00:00','2026-01-10T05:00:00',0,'2026-01-10T11:05:00',NEWID()),
            (CONVERT(uniqueidentifier,?),'2026-01-10T11:00:00','2026-01-10T05:00:00',8,'2026-01-10T11:05:00',NEWID()),
            (CONVERT(uniqueidentifier,?),'2026-01-10T11:00:00','2026-01-10T05:00:00',5,'2026-01-10T11:05:00',NEWID())
        """,
        SENSOR_A,
        SENSOR_C,
        SENSOR_B);

    var result =
        repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.sensors())
        .extracting("sensorId")
        .containsExactly(SENSOR_A, SENSOR_C, SENSOR_B);
  }

  private ModuleProperties properties() {
    ModuleProperties properties = new ModuleProperties();
    properties.getWarehouse().setHost(SQL_SERVER.getHost());
    properties.getWarehouse().setPort(SQL_SERVER.getFirstMappedPort());
    properties.getWarehouse().setUsername(SQL_SERVER.getUsername());
    properties.getWarehouse().setPassword(SQL_SERVER.getPassword());
    properties.getWarehouse().setEncrypt(false);
    properties.getWarehouse().setTrustServerCertificate(true);
    ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
    tenant.setDisplayName("Tenant test");
    tenant.setDatabase("master");
    properties.getTenants().put("tenant-test", tenant);
    return properties;
  }
}
