package com.eloarena;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context loads against real Postgres and Redis,
 * migrations apply, and all beans wire up.
 */
class EloArenaMatchmakerApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

}
