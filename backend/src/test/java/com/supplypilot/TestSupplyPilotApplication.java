package com.supplypilot;

import org.springframework.boot.SpringApplication;

public class TestSupplyPilotApplication {

	public static void main(String[] args) {
		SpringApplication.from(SupplyPilotApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
