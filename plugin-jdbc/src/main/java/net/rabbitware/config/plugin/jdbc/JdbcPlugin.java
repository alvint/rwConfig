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

    @Override
    public boolean isChangeDetectionSupported() {
        return false;
    }

    @Override
    public void startChangeDetection() throws Exception {
        // logger.info("JDBC plugin started change detection");
    }

    @Override
    public void stopChangeDetection() throws Exception {
        // logger.info("JDBC plugin stopped change detection");
    }

    @Override
    public boolean isChanged() {
        return false;
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("connectionString", "query"); // required properties for JDBC plugin
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of("username", "password"); // optional properties for JDBC plugin
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
