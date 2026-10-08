package com.duma.experience.config;

import com.duma.core.config.ModuleProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TenantDataSourceRegistry {

  private final ModuleProperties properties;
  private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

  public TenantDataSourceRegistry(ModuleProperties properties) {
    this.properties = properties;
  }

  public JdbcTemplate jdbc(String tenantId) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (tenant == null
        || !tenant.isEnabled()
        || tenant.getDatabase() == null
        || tenant.getDatabase().isBlank()) {
      throw new IllegalArgumentException("Tenant no registrado o deshabilitado.");
    }
    HikariDataSource dataSource =
        dataSources.computeIfAbsent(tenantId, ignored -> create(tenantId, tenant));
    return new JdbcTemplate(dataSource);
  }

  private HikariDataSource create(String tenantId, ModuleProperties.Tenant tenant) {
    ModuleProperties.Warehouse warehouse = properties.getWarehouse();
    String host = tenant.getHost() == null || tenant.getHost().isBlank() ? warehouse.getHost() : tenant.getHost();
    int port = tenant.getPort() == null ? warehouse.getPort() : tenant.getPort();
    String username = tenant.getUsername() == null ? warehouse.getUsername() : tenant.getUsername();
    String password = tenant.getPassword() == null ? warehouse.getPassword() : tenant.getPassword();
    boolean encrypt = tenant.getEncrypt() == null ? warehouse.isEncrypt() : tenant.getEncrypt();
    boolean trustServerCertificate =
        tenant.getTrustServerCertificate() == null
            ? warehouse.isTrustServerCertificate()
            : tenant.getTrustServerCertificate();
    int poolSizePerTenant =
        tenant.getPoolSizePerTenant() == null
            ? warehouse.getPoolSizePerTenant()
            : tenant.getPoolSizePerTenant();
    HikariConfig config = new HikariConfig();
    config.setPoolName("experience-" + tenantId);
    config.setJdbcUrl(
        "jdbc:sqlserver://"
            + host
            + ":"
            + port
            + ";databaseName="
            + tenant.getDatabase()
            + ";encrypt="
            + encrypt
            + ";trustServerCertificate="
            + trustServerCertificate);
    config.setUsername(username);
    config.setPassword(password);
    config.setMinimumIdle(1);
    config.setMaximumPoolSize(poolSizePerTenant);
    config.setConnectionTimeout(10_000);
    config.setValidationTimeout(5_000);
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }

  @PreDestroy
  void close() {
    dataSources.values().forEach(HikariDataSource::close);
  }
}
