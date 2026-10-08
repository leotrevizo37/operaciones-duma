package com.duma.experience.config;

import com.duma.core.domain.CoverageStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.duma.core.config.ModuleProperties;
import com.duma.experience.data.ExperienceRepository;
import com.duma.experience.domain.ExperienceDashboard;
import java.time.LocalDate;
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
class ExperienceRepositoryIT {

  @Container
  private static final MSSQLServerContainer<?> SQL_SERVER =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

  private TenantDataSourceRegistry registry;
  private ExperienceRepository repository;
  private JdbcTemplate jdbc;

  @BeforeAll
  void connect() {
    ModuleProperties properties = properties();
    registry = new TenantDataSourceRegistry(properties);
    repository = new ExperienceRepository(properties, registry);
    jdbc = registry.jdbc("tenant-test");
    jdbc.execute("IF SCHEMA_ID(N'observability') IS NULL EXEC(N'CREATE SCHEMA observability')");
  }

  @BeforeEach
  void resetTables() {
    jdbc.execute(
        "IF OBJECT_ID(N'observability.factSidonUserUsage',N'U') IS NOT NULL DROP TABLE observability.factSidonUserUsage");
    jdbc.execute(
        "IF OBJECT_ID(N'observability.factUrlAvailabilityDaily',N'U') IS NOT NULL DROP TABLE observability.factUrlAvailabilityDaily");
    jdbc.execute(
        "IF OBJECT_ID(N'observability.dimAspNetUsers',N'U') IS NOT NULL DROP TABLE observability.dimAspNetUsers");
    jdbc.execute(
        """
                CREATE TABLE observability.factSidonUserUsage(
                    UserId nvarchar(80) NOT NULL,
                    [Date] date NOT NULL,
                    HasConnected bit NOT NULL,
                    MadeCompleteInteraction bit NOT NULL,
                    TimeConnected int NULL,
                    AvgLatency float NULL,
                    Latency95thPercentile float NULL
                )
                """);
    jdbc.execute(
        """
                CREATE TABLE observability.factUrlAvailabilityDaily(
                    Url varchar(850) NOT NULL,
                    [Date] date NOT NULL,
                    UptimePercentage decimal(7,4) NOT NULL,
                    AvgLatencySeconds decimal(18,6) NOT NULL,
                    Latency95thPercentileSeconds decimal(18,6) NOT NULL,
                    IsUp bit NOT NULL,
                    TimeoutsPresent bit NOT NULL,
                    ModifiedAt datetime2(0) NOT NULL
                )
                """);
    jdbc.execute(
        """
                CREATE TABLE observability.dimAspNetUsers(
                    Id nvarchar(80) NOT NULL,
                    UserName nvarchar(256) NULL,
                    Name nvarchar(256) NULL,
                    LastName nvarchar(256) NULL,
                    Position nvarchar(256) NULL
                )
                """);
  }

  @AfterAll
  void close() {
    registry.close();
  }

  @Test
  void combinesUserExperienceAndAvailabilityWithoutLosingTheirBaselines() {
    jdbc.update(
        """
                INSERT INTO observability.factSidonUserUsage VALUES
                    (N'u1','2026-01-10',1,1,120,100,150),
                    (N'u2','2026-01-10',1,0,240,2500,3000)
                """);
    jdbc.update(
        """
                INSERT INTO observability.dimAspNetUsers VALUES
                    (N'u1',N'ada@sidon.mx',N'Ada',N'Lovelace',N'Analista')
                """);
    jdbc.update(
        """
                INSERT INTO observability.factUrlAvailabilityDaily VALUES
                    ('https://service-a.invalid','2026-01-10',99.0000,0.100000,0.200000,1,0,'2026-01-10T23:00:00'),
                    ('https://service-b.invalid','2026-01-10',90.0000,1.500000,2.500000,0,1,'2026-01-10T23:00:00')
                """);

    var result =
        repository.load("tenant-test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.AVAILABLE);
    assertThat(result.missingSources()).isEmpty();
    assertThat(result.current().users().evaluatedUserDays()).isEqualTo(2);
    assertThat(result.current().users().completeInteractions()).isEqualTo(1);
    assertThat(result.current().users().avgLatencyMs()).isCloseTo(1300.0, within(0.001));
    assertThat(result.current().users().slowUserDays()).isEqualTo(1);
    assertThat(result.current().availability().observedServiceDays()).isEqualTo(2);
    assertThat(result.current().availability().avgUptimePercentage())
        .isCloseTo(94.5, within(0.001));
    assertThat(result.current().availability().currentDownServices()).isEqualTo(1);
    assertThat(result.users())
        .extracting(
            ExperienceDashboard.UserExperience::userId,
            ExperienceDashboard.UserExperience::displayName)
        .containsExactlyInAnyOrder(tuple("u1", "Ada Lovelace"), tuple("u2", "u2"));
    assertThat(result.previous().observedRows()).isZero();
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
