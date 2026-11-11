package es.jllopez.neis.infrastructure;

import org.hibernate.community.dialect.SQLiteDialect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test of the persistence layer (US-02.1, issue #15): the ORM boots
 * against SQLite3 through the managed sqlite-jdbc driver and the
 * Hibernate community {@link SQLiteDialect}.
 *
 * <p>Runs against an in-memory SQLite database so no test artifact lands
 * in the working directory. The interactive Spring Shell loop is disabled
 * in the test-scope properties (see src/test/resources).</p>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
class PersistenceSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Test
    void datasourceIsSqlite() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertEquals("SQLite", meta.getDatabaseProductName());
            assertTrue(meta.getURL().startsWith("jdbc:sqlite:"),
                    "JDBC url should be SQLite, was: " + meta.getURL());
        }
    }

    @Test
    void jdbcTemplateExecutesAgainstSqlite() {
        assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Integer.class));
    }

    @Test
    void hibernateUsesTheSqliteDialect() {
        Object dialect = entityManagerFactory.getProperties().get("hibernate.dialect");
        // Hibernate keeps the dialect either as an instance or as the class
        // name string, depending on how it was resolved.
        String dialectName = dialect instanceof String s ? s : dialect.getClass().getName();
        assertEquals(SQLiteDialect.class.getName(), dialectName);
    }
}
