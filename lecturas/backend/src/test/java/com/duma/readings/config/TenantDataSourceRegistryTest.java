package com.duma.readings.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.config.ModuleProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class TenantDataSourceRegistryTest {

  @Test
  void usesTheTenantConnectionInsteadOfTheLegacyWarehouseFallback() {
    ModuleProperties properties = new ModuleProperties();
    properties.getWarehouse().setHost("warehouse-fallback");
    ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
    tenant.setDatabase("tenant_database");
    tenant.setHost("tenant-host");
    tenant.setPort(1444);
    tenant.setUsername("tenant-user");
    tenant.setPassword("tenant-password");
    tenant.setEncrypt(false);
    tenant.setTrustServerCertificate(true);
    tenant.setPoolSizePerTenant(3);
    properties.getTenants().put("carlsjr", tenant);

    TenantDataSourceRegistry registry = new TenantDataSourceRegistry(properties);
    HikariDataSource dataSource = (HikariDataSource) registry.jdbc("carlsjr").getDataSource();

    assertThat(dataSource.getJdbcUrl())
        .contains("//tenant-host:1444;databaseName=tenant_database;encrypt=false;trustServerCertificate=true");
    assertThat(dataSource.getUsername()).isEqualTo("tenant-user");
    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
    assertThat(dataSource.getMinimumIdle()).isEqualTo(1);
    registry.close();
  }

  @Test
  void bindsTenantConnectionFromEnvironmentVariables() {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "application", Map.of("duma.tenants.carlsjr.display-name", "Carls Jr")));
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test",
                Map.of(
                    "DUMA_TENANTS_CARLSJR_HOST", "tenant-host",
                    "DUMA_TENANTS_CARLSJR_DATABASE", "tenant_database",
                    "DUMA_TENANTS_CARLSJR_USERNAME", "tenant-user",
                    "DUMA_TENANTS_CARLSJR_PASSWORD", "tenant-password")));

    ModuleProperties properties =
        Binder.get(environment)
            .bind("duma", Bindable.of(ModuleProperties.class))
            .orElseThrow(IllegalStateException::new);

    assertThat(properties.getTenants().get("carlsjr").getHost()).isEqualTo("tenant-host");
    assertThat(properties.getTenants().get("carlsjr").getDatabase())
        .isEqualTo("tenant_database");
    assertThat(properties.getTenants().get("carlsjr").getUsername()).isEqualTo("tenant-user");
    assertThat(properties.getTenants().get("carlsjr").getPassword())
        .isEqualTo("tenant-password");
  }

  @Test
  void servesOneSharedPoolToConcurrentCallersOfTheSameTenant() throws Exception {
    ModuleProperties properties = new ModuleProperties();
    ModuleProperties.Tenant tenant = new ModuleProperties.Tenant();
    tenant.setDatabase("tenant_database");
    properties.getTenants().put("carlsjr", tenant);

    TenantDataSourceRegistry registry = new TenantDataSourceRegistry(properties);
    ExecutorService callers = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<DataSource>> pools = new ArrayList<>();
    for (int caller = 0; caller < 16; caller++) {
      pools.add(
          callers.submit(
              () -> {
                start.await();
                return registry.jdbc("carlsjr").getDataSource();
              }));
    }
    start.countDown();
    callers.shutdown();
    assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    DataSource shared = pools.get(0).get();
    for (Future<DataSource> pool : pools) {
      assertThat(pool.get()).isSameAs(shared);
    }
    registry.close();
  }
}
