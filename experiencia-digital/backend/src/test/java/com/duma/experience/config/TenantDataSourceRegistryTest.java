package com.duma.experience.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duma.core.config.ModuleProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

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
