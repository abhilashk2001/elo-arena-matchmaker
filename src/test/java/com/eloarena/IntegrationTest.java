package com.eloarena;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for integration tests. Boots the full application context against real
 * Postgres 16 and Redis 7 containers (see {@link TestcontainersConfiguration}).
 *
 * We test against real infrastructure rather than mocked repositories because the
 * behaviour this project depends on cannot be mocked: row locking with
 * FOR UPDATE SKIP LOCKED, partial unique indexes, transaction rollback, and real
 * query plans only exist in an actual database.
 *
 * Every subclass shares the same context configuration, so Spring caches one context
 * and one set of containers across the whole suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// The scheduled matcher loop is off by default in tests so it cannot run in the background
// and match queued players mid-test. Tests that want the loop re-enable it explicitly.
@TestPropertySource(properties = "eloarena.matcher.loop-enabled=false")
public abstract class IntegrationTest {
}
