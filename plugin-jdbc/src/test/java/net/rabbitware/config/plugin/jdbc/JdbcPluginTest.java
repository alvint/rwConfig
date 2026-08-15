package net.rabbitware.config.plugin.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the JDBC plugin, which runs a query and uses the first two columns
 * of each row as a property name and value.
 *
 * <p>These run against an in-memory database, so they need no setup beyond the
 * driver being on the test classpath.
 */
class JdbcPluginTest {

    private static final String URL = "jdbc:h2:mem:jdbc-plugin-test;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "test-password";

    @BeforeEach
    void createTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS config_properties");
            statement.execute(
                "CREATE TABLE config_properties (property_key VARCHAR(255), property_value VARCHAR(255))"
            );
            statement.execute(
                "INSERT INTO config_properties VALUES ('greeting', 'hello from the database!'),"
                + " ('answer', '42')"
            );
        }
    }

    @AfterEach
    void dropTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS config_properties");
        }
    }

    /** Run the plugin with the given properties, filling in the usual ones. */
    private Map<String, String> load(Map<String, String> overrides) throws Exception {
        Map<String, String> properties = new HashMap<>(Map.of(
            "connectionString", URL,
            "query", "SELECT property_key, property_value FROM config_properties",
            "username", USER,
            "password", PASSWORD
        ));
        properties.putAll(overrides);
        JdbcPlugin plugin = new JdbcPlugin();
        plugin.setSourceName("db");
        plugin.setPluginProperties(properties);
        return plugin.getConfigSourceProperties();
    }


    @Test
    @DisplayName("the query results become properties, keyed by the first column")
    void queryResultsBecomeProperties() throws Exception {
        Map<String, String> properties = load(Map.of());
        assertEquals("hello from the database!", properties.get("greeting"));
        assertEquals("42", properties.get("answer"));
        assertEquals(2, properties.size());
    }

    @Test
    @DisplayName("`SELECT *` works as long as the first two columns are the name and value")
    void selectStarWorks() throws Exception {
        Map<String, String> properties = load(Map.of("query", "SELECT * FROM config_properties"));
        assertEquals("hello from the database!", properties.get("greeting"));
    }

    @Test
    void aQueryThatReturnsNothingProducesNoProperties() throws Exception {
        Map<String, String> properties = load(
            Map.of("query", "SELECT property_key, property_value FROM config_properties WHERE 1 = 0")
        );
        assertTrue(properties.isEmpty());
    }

    @Test
    void theDeclaredRequiredAndOptionalProperties() {
        JdbcPlugin plugin = new JdbcPlugin();
        assertEquals(Set.of("connectionString", "query"), plugin.getRequiredPluginPropertyNames());
        assertEquals(Set.of("username", "password"), plugin.getOptionalPluginPropertyNames());
    }

    @Test
    @DisplayName("the username and password are optional, as the documentation says")
    void credentialsAreOptional() throws Exception {
        // a database that needs no credentials, to show the plugin does not
        // require them to be supplied
        String url = "jdbc:h2:mem:jdbc-no-credentials;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS t (k VARCHAR(255), v VARCHAR(255))");
            statement.execute("DELETE FROM t");
            statement.execute("INSERT INTO t VALUES ('k1', 'v1')");
        }
        JdbcPlugin plugin = new JdbcPlugin();
        plugin.setSourceName("db");
        plugin.setPluginProperties(Map.of("connectionString", url, "query", "SELECT k, v FROM t"));
        assertEquals("v1", plugin.getConfigSourceProperties().get("k1"));
    }

    @Test
    void aMissingConnectionStringIsRejected() {
        JdbcPlugin plugin = new JdbcPlugin();
        plugin.setSourceName("db");
        assertThrows(Exception.class, () -> plugin.setPluginProperties(Map.of("query", "SELECT 1")));
    }

    @Test
    void aMissingQueryIsRejected() {
        JdbcPlugin plugin = new JdbcPlugin();
        plugin.setSourceName("db");
        assertThrows(
            Exception.class,
            () -> plugin.setPluginProperties(Map.of("connectionString", URL))
        );
    }

    @Test
    @DisplayName("a query against a missing table is reported rather than swallowed")
    void aBadQueryIsReported() {
        assertThrows(
            Exception.class,
            () -> load(Map.of("query", "SELECT a, b FROM no_such_table"))
        );
    }

    @Test
    void theVersionIsReported() {
        assertTrue(new JdbcPlugin().getPluginVersion() != null);
    }
}
