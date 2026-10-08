package com.duma.readings.api;

import com.duma.core.domain.CoverageStatus;
import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.config.ModuleProperties;
import com.duma.readings.data.ReadingsRepository;
import com.duma.readings.domain.ReadingsDashboard;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReadingsControllerTest {

  private static final List<String> TENANTS = List.of("carlsjr", "emerson", "valledelencino");

  @Test
  void queriesEveryTenantAtOnce() {
    ModuleProperties properties = propertiesWith(TENANTS);
    // Detects sequential execution: each tenant waits for all others.
    CountDownLatch arrived = new CountDownLatch(TENANTS.size());
    ReadingsController controller =
        new ReadingsController(properties, new RendezvousRepository(properties, arrived));

    ReadingsDashboard.Response response = controller.dashboard(null, null, null, false);

    assertThat(response.tenants()).hasSize(TENANTS.size());
    assertThat(arrived.getCount()).isZero();
    controller.close();
  }

  @Test
  void keepsTheConfiguredTenantOrder() {
    ModuleProperties properties = propertiesWith(TENANTS);
    CountDownLatch arrived = new CountDownLatch(TENANTS.size());
    ReadingsController controller =
        new ReadingsController(properties, new RendezvousRepository(properties, arrived));

    ReadingsDashboard.Response response = controller.dashboard(null, null, null, false);

    assertThat(response.tenants())
        .extracting(ReadingsDashboard.TenantResult::tenantId)
        .containsExactlyElementsOf(TENANTS);
    controller.close();
  }

  private static ModuleProperties propertiesWith(List<String> tenantIds) {
    ModuleProperties properties = new ModuleProperties();
    for (String tenantId : tenantIds) {
      ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
      tenant.setDisplayName(tenantId);
      tenant.setDatabase(tenantId + "_database");
      properties.getTenants().put(tenantId, tenant);
    }
    return properties;
  }

  private static final class RendezvousRepository extends ReadingsRepository {
    private final CountDownLatch arrived;

    private RendezvousRepository(ModuleProperties properties, CountDownLatch arrived) {
      super(properties, null);
      this.arrived = arrived;
    }

    @Override
    public ReadingsDashboard.TenantResult load(String tenantId, LocalDate from, LocalDate to, boolean includeDiagnostics) {
      arrived.countDown();
      try {
        if (!arrived.await(5, TimeUnit.SECONDS))
          throw new IllegalStateException(
              "Los tenants no coincidieron: el abanico esta saliendo en serie.");
      } catch (InterruptedException interruption) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interruption);
      }
      return new ReadingsDashboard.TenantResult(
          tenantId,
          tenantId,
          CoverageStatus.AVAILABLE,
          List.of(),
          ReadingsDashboard.Summary.empty(),
          ReadingsDashboard.Summary.empty(),
          List.of(),
          List.of(),
          List.of(),
          null);
    }
  }
}
