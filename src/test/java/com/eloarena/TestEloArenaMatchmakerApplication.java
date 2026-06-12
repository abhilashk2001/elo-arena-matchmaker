package com.eloarena;

import org.springframework.boot.SpringApplication;

public class TestEloArenaMatchmakerApplication {

	public static void main(String[] args) {
		SpringApplication.from(EloArenaMatchmakerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
