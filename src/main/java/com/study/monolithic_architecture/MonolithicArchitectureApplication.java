package com.study.monolithic_architecture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MonolithicArchitectureApplication {

	public static void main(String[] args) {
		SpringApplication.run(MonolithicArchitectureApplication.class, args);
	}

}
