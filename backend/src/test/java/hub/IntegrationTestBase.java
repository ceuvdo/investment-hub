package hub;

import hub.daily.CatalogBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared Spring context for DB-backed tests: database hub_test (never hub), random port, live feeds off,
 * deploy off, relay unset (see application-test.yml). CatalogBuilder is a mock so no build touches the network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
    @MockitoBean
    protected CatalogBuilder catalogBuilder;

    @Autowired
    protected JdbcTemplate jdbc;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void guardDatabase() {
        assertEquals("hub_test", jdbc.queryForObject("SELECT current_database()", String.class),
                "tests must never touch the production database");
    }
}
