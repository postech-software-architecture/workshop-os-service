package com.postech.foundation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.boot.autoconfigure.domain.EntityScan("com.postech.workshop_service.infrastructure.persistence.entities")
@org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.postech.workshop_service.infrastructure.persistence.repositories")
@SpringBootApplication(scanBasePackages = { "com.postech.foundation", "com.postech.workshop_service" })
@EnableScheduling
@io.swagger.v3.oas.annotations.security.SecurityScheme(name = "bearerAuth",
		type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
