package com.duma.experience.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.duma.core.config.ModuleProperties;
import com.duma.core.domain.CoverageStatus;
import com.duma.experience.config.TenantDataSourceRegistry;
import com.duma.experience.domain.ExperienceDashboard;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ExperienceRepositoryTest {

  private static final String USER_SOURCE = "observability.factSidonUserUsage";
  private static final String USER_DIMENSION = "observability.dimAspNetUsers";
  private static final String AVAILABILITY_SOURCE = "observability.factUrlAvailabilityDaily";

  /**
   * One source present, the other absent, and no rows behind the one that is there.
   *
   * <p>This module holds <b>two independent sources</b> and combines them with OR. The shared
   * resolver takes a source and its readability, which is a different shape: feeding these two
   * flags in positionally would answer UNAVAILABLE — a tenant told the service failed when what
   * actually happened is that it has nothing to report yet.
   */
  @Test
  void oneSourcePresentWithNoRowsIsEmptyDataAndNotAFailure() {
    ModuleProperties properties = new ModuleProperties();
    ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
    tenant.setDisplayName("Tenant con usuarios y sin filas");
    tenant.setDatabase("warehouse");
    properties.getTenants().put("empty", tenant);
    TenantDataSourceRegistry registry = mock(TenantDataSourceRegistry.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(registry.jdbc("empty")).thenReturn(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(USER_SOURCE))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(USER_DIMENSION))).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(AVAILABILITY_SOURCE))).thenReturn(0);
    // Without this the mock hands back null and observedRows() throws before classifying.
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(ExperienceDashboard.UserMetrics.empty());

    var result =
        new ExperienceRepository(properties, registry)
            .load("empty", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.NO_DATA);
    assertThat(result.current().observedRows()).isZero();
    assertThat(result.missingSources()).contains(AVAILABILITY_SOURCE);
    assertThat(result.errorCode()).isNull();
  }
}
