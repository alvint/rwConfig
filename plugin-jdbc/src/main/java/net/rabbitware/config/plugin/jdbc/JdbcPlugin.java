package net.rabbitware.config.plugin.jdbc;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

/**
 * A simple jdbc plugin implementation. It retrieves configuration properties
 * from a JDBC source.
 * <p>
 * It requires two properties to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.connectionString} - the JDBC connection string.
 * </li>
 * <li>
 * {@code rwc.<sourceName>.query} - the SQL query to execute. The query must
 * return at least two columns: the first column is the property key, and the
 * second column is the property value.
 * </li>
 * </ul>
 * </p>
 * There are two optional properties that can be set in the {@code rwconfig}
 * file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.username} - the username for the JDBC connection.
 * </li>
 * <li>
 * {@code rwc.<sourceName>.password} - the password for the JDBC connection.
 * </li>
 * </ul>
 */
public class JdbcPlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(JdbcPlugin.class);
    private String sourceName;
    private String connectionString;
    private String query;
    private String username;
    private String password;

    /**
     * An optional query whose result says whether the config has changed - see
     * {@link #isChanged()}. This is ull when the source was declared without
     * one, which is what makes change detection unsupported for this source.
     */
    private String changeQuery;

    /** The last value {@code changeQuery} returned, or null before the first check. */
    private String lastChangeValue;

    public JdbcPlugin() {
        logger.info("JDBC plugin instantiated");
    } 


    @Override
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
        logger.info("source name set to: {}", this.sourceName);
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Supported only when the source declares a {@code changeQuery}. There is
     * no portable way to ask a database whether a table has changed; the answer
     * has to come either from something vendor-specific or from the data
     * itself.
     */
    @Override
    public boolean isChangeDetectionSupported() {
        return changeQuery != null;
    }

    @Override
    public void startChangeDetection() throws Exception {
        if (changeQuery == null) {
            throw new IllegalStateException("change detection needs a `changeQuery` property");
        }
        // take the reading the first check will be compared against, so that a
        // value which has not changed since startup is not reported as a change
        lastChangeValue = runChangeQuery();
        logger.debug("change detection started for source `{}`, initial value: {}", sourceName, lastChangeValue);
    }

    @Override
    public void stopChangeDetection() throws Exception {
        lastChangeValue = null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Run the {@code changeQuery} and compares its result with the previous
     * one. The new value is kept whether or not it differs, so a change is
     * reported once rather than on every poll.
     */
    @Override
    public boolean isChanged() throws Exception {
        String current = runChangeQuery();
        boolean changed = !java.util.Objects.equals(current, lastChangeValue);
        if (changed) {
            logger.debug(
                "source `{}` changed - change query returned `{}`, was `{}`", sourceName, current, lastChangeValue
            );
        }
        lastChangeValue = current;
        return changed;
    }

    /**
     * Run the change query and return its first column of its first row as
     * text.
     * <p>
     * Read as a string whatever the column's type, because the value is only
     * ever compared with the previous one - a timestamp, a row count, a version
     * number and a checksum all work, and none of them needs interpreting.
     *
     * @return
     * the value, or null if the query returned no rows
     * @throws Exception
     * if the query cannot be run
     */
    private String runChangeQuery() throws Exception {
        try (var connection = DriverManager.getConnection(connectionString, username, password);
             var statement = connection.prepareStatement(changeQuery);
             var resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null; // an empty result is a legitimate answer, and a stable one
            }
            return resultSet.getString(1);
        }
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("connectionString", "query"); // required properties for JDBC plugin
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of("username", "password", "changeQuery"); // optional properties for JDBC plugin
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        logger.info("setting plugin properties: {}", properties);
        // set and validate required properties
        connectionString = properties.get("connectionString");
        if (connectionString == null || connectionString.isBlank()) {
            throw new Exception("missing required property: connectionString");
        }
        query = properties.get("query");
        if (query == null || query.isBlank()) {
            throw new Exception("missing required property: query");
        }
        username = properties.get("username");
        password = properties.get("password");
        changeQuery = properties.get("changeQuery");
        if (changeQuery != null && changeQuery.isBlank()) {
            throw new Exception("`changeQuery` is set but empty - remove it, or give it a query");
        }
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        try (var connection = DriverManager.getConnection(connectionString, username, password)) {
            var preparedStatement = connection.prepareStatement(query);
            try (var resultSet = preparedStatement.executeQuery()) {
                int columnCount = resultSet.getMetaData().getColumnCount();
                if (columnCount < 2) {
                    throw new Exception("query must return at least two columns: key and value");
                }
                var configProperties = new java.util.HashMap<String, String>();
                while (resultSet.next()) {
                    String key = resultSet.getString(1);
                    String value = resultSet.getString(2);
                    configProperties.put(key, value);
                }
                logger.info("retrieved {} properties from JDBC source", configProperties.size());
                return configProperties;
            }
        }
    }
}
