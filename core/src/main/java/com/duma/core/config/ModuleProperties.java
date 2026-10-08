package com.duma.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "duma")
public class ModuleProperties {
  private final Module module = new Module();
  private final Warehouse warehouse = new Warehouse();
  private final Security security = new Security();
  private final Map<String, Tenant> tenants = new LinkedHashMap<>();

  public Module getModule() {
    return module;
  }

  public Warehouse getWarehouse() {
    return warehouse;
  }

  public Security getSecurity() {
    return security;
  }

  public Map<String, Tenant> getTenants() {
    return tenants;
  }

  public static class Module {
    private String id = "";
    private String displayName = "";
    private String apiBaseUrl = "";
    private String releaseStage = "DEVELOPMENT";
    private String dataEnvironment = "PRODUCTION";
    private String freshnessMode = "LIVE";
    private String clearance = "ACADEMIC_PRIVATE";
    private String tenantScope = "ALL_TENANTS";
    private List<String> capabilities = new ArrayList<>();

    public String getId() {
      return id;
    }

    public void setId(String value) {
      id = value;
    }

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String value) {
      displayName = value;
    }

    public String getApiBaseUrl() {
      return apiBaseUrl;
    }

    public void setApiBaseUrl(String value) {
      apiBaseUrl = value;
    }

    public String getReleaseStage() {
      return releaseStage;
    }

    public void setReleaseStage(String value) {
      releaseStage = value;
    }

    public String getDataEnvironment() {
      return dataEnvironment;
    }

    public void setDataEnvironment(String value) {
      dataEnvironment = value;
    }

    public String getFreshnessMode() {
      return freshnessMode;
    }

    public void setFreshnessMode(String value) {
      freshnessMode = value;
    }

    public String getClearance() {
      return clearance;
    }

    public void setClearance(String value) {
      clearance = value;
    }

    public String getTenantScope() {
      return tenantScope;
    }

    public void setTenantScope(String value) {
      tenantScope = value;
    }

    public List<String> getCapabilities() {
      return capabilities;
    }

    public void setCapabilities(List<String> value) {
      capabilities = value;
    }
  }

  public static class Warehouse {
    private String host = "localhost";
    private int port = 1433;
    private String username;
    private String password;
    private boolean encrypt = true;
    private boolean trustServerCertificate;
    private int poolSizePerTenant = 2;

    public String getHost() {
      return host;
    }

    public void setHost(String value) {
      host = value;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int value) {
      port = value;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String value) {
      username = value;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String value) {
      password = value;
    }

    public boolean isEncrypt() {
      return encrypt;
    }

    public void setEncrypt(boolean value) {
      encrypt = value;
    }

    public boolean isTrustServerCertificate() {
      return trustServerCertificate;
    }

    public void setTrustServerCertificate(boolean value) {
      trustServerCertificate = value;
    }

    public int getPoolSizePerTenant() {
      return poolSizePerTenant;
    }

    public void setPoolSizePerTenant(int value) {
      poolSizePerTenant = value;
    }
  }

  public static class Security {
    private String issuer;
    private String jwkSetUri;
    private boolean standaloneMode;
    // Origins are module-specific; a shared default could expose another module's frontend.
    private List<String> allowedOrigins = new ArrayList<>();

    public String getIssuer() {
      return issuer;
    }

    public void setIssuer(String value) {
      issuer = value;
    }

    public String getJwkSetUri() {
      return jwkSetUri;
    }

    public void setJwkSetUri(String value) {
      jwkSetUri = value;
    }

    public boolean isStandaloneMode() {
      return standaloneMode;
    }

    public void setStandaloneMode(boolean value) {
      standaloneMode = value;
    }

    public List<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> value) {
      allowedOrigins = value;
    }
  }

  public static class Tenant {
    private String displayName;
    private String database;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private Boolean encrypt;
    private Boolean trustServerCertificate;
    private Integer poolSizePerTenant;
    private boolean enabled = true;

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String value) {
      displayName = value;
    }

    public String getDatabase() {
      return database;
    }

    public void setDatabase(String value) {
      database = value;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String value) {
      host = value;
    }

    public Integer getPort() {
      return port;
    }

    public void setPort(Integer value) {
      port = value;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String value) {
      username = value;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String value) {
      password = value;
    }

    public Boolean getEncrypt() {
      return encrypt;
    }

    public void setEncrypt(Boolean value) {
      encrypt = value;
    }

    public Boolean getTrustServerCertificate() {
      return trustServerCertificate;
    }

    public void setTrustServerCertificate(Boolean value) {
      trustServerCertificate = value;
    }

    public Integer getPoolSizePerTenant() {
      return poolSizePerTenant;
    }

    public void setPoolSizePerTenant(Integer value) {
      poolSizePerTenant = value;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }
  }
}
