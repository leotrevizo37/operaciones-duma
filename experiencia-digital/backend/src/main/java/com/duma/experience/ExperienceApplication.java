package com.duma.experience;

import com.duma.core.config.ModuleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

// com.duma.core queda fuera del paquete base y contiene el manifiesto y la auditoría compartidos.
@SpringBootApplication(scanBasePackages = {"com.duma.experience", "com.duma.core"})
@EnableAsync
@EnableConfigurationProperties(ModuleProperties.class)
public class ExperienceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExperienceApplication.class, args);
  }
}
