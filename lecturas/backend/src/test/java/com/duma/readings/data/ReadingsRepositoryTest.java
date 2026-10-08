package com.duma.readings.data;

import com.duma.core.domain.CoverageStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duma.core.config.ModuleProperties;
import com.duma.readings.config.TenantDataSourceRegistry;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReadingsRepositoryTest {
  private static final String FACT = "observability.factRedingsAudits";
  private static final String MEASUREMENTS = "dwh.factReadingsMeasurement";

  private static final String OBJECT_EXISTS = "SELECT CASE WHEN OBJECT_ID";
  private static final String HAS_SELECT = "SELECT HAS_PERMS_BY_NAME";

  @Test
  void reportsNotSupportedWhenRequiredFactIsMissing() {
    ModuleProperties properties = new ModuleProperties();
    ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
    tenant.setDisplayName("Tenant sin lecturas");
    tenant.setDatabase("warehouse");
    properties.getTenants().put("empty", tenant);
    TenantDataSourceRegistry registry = mock(TenantDataSourceRegistry.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(registry.jdbc("empty")).thenReturn(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);

    var result =
        new ReadingsRepository(properties, registry)
            .load("empty", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.NOT_SUPPORTED);
    assertThat(result.missingSources()).contains("observability.factRedingsAudits");
    assertThat(result.current().sensorsObserved()).isZero();
    assertThat(result.errorCode()).isNull();
  }

  /**
   * The fact table is there and this module cannot read it.
   *
   * <p>This is the only module that tells the two apart, and the distinction is the whole reason
   * the shared resolver takes readability as its own argument: a permission the tenant never
   * granted is a failure to answer (UNAVAILABLE, with an error code), while a table that was never
   * built is simply outside coverage (NOT_SUPPORTED, no error).
   */
  @Test
  void reportsUnavailableWhenTheFactIsPresentButUnreadable() {
    ModuleProperties properties = new ModuleProperties();
    ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
    tenant.setDisplayName("Tenant sin permiso de lectura");
    tenant.setDatabase("warehouse");
    properties.getTenants().put("denied", tenant);
    TenantDataSourceRegistry registry = mock(TenantDataSourceRegistry.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(registry.jdbc("denied")).thenReturn(jdbc);
    // Broadest stub first: a later matching stub wins in Mockito, so the specific ones follow.
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    when(jdbc.queryForObject(startsWith(OBJECT_EXISTS), eq(Integer.class), eq(FACT))).thenReturn(1);
    when(jdbc.queryForObject(startsWith(HAS_SELECT), eq(Integer.class), eq(FACT))).thenReturn(0);

    var result =
        new ReadingsRepository(properties, registry)
            .load("denied", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.UNAVAILABLE);
    assertThat(result.missingSources()).contains(FACT);
    assertThat(result.errorCode()).isEqualTo("TENANT_QUERY_FAILED");
  }
}
