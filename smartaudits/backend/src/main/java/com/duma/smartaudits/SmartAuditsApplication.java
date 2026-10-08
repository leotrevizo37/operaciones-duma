package com.duma.smartaudits;

import com.duma.core.config.ModuleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

// Core is outside this package's default component scan.
@SpringBootApplication(scanBasePackages = {"com.duma.smartaudits", "com.duma.core"})
@EnableAsync
@EnableConfigurationProperties(ModuleProperties.class)
public class SmartAuditsApplication {
  public static void main(String[] args) {
    SpringApplication.run(SmartAuditsApplication.class, args);
  }
}
