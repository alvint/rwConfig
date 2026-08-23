package net.rabbitware.config.plugin.environmentvariables;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnvironmentVariablesPlugin implements SimpleConfigSourcePlugin {
    private static Logger logger = LoggerFactory.getLogger(EnvironmentVariablesPlugin.class);

    private String sourceName;
    private final Set<String> propertyNames;

    public EnvironmentVariablesPlugin(Set<String> propertyNames) {
        this.propertyNames = propertyNames;
        logger.info("environment plugin instantiated");
    }

    
    @Override
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
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
        throw new UnsupportedOperationException("change detection is not supported");
    }

    @Override
    public void stopChangeDetection() throws Exception {
        throw new UnsupportedOperationException("change detection is not supported");
    }

    @Override
    public boolean isChanged() throws Exception {
        throw new UnsupportedOperationException("change detection is not supported");
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return null;
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return null;
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // not used
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        Map<String, String> out = new EnvironmentProperties(propertyNames);
        logger.info("config source `{}` loaded from environment variables", sourceName);
        return out;
    }


    //
    // public helper methods
    //

    /**
     * Retrieve the value of the environment variable corresponding to the given
     * property name. The environment is first searched using the property name
     * as-is. If the environment variable is not found under that name, the
     * property name is converted to an environment-variable-friendly name and
     * the environment is searched again. This conversion is done by replacing
     *  camelCase and dots with underscores, and then converting to uppercase.
     *
     * @param propertyName
     * the name of the environment variable to retrieve
     * @return
     * the value of the environment variable, or {@code null} if not found
     */
    public static String getEnvironmentVariable(String propertyName) {
        String value = System.getenv(propertyName); // try to get the property value from the environment variables
        if (value == null) { // not found - retry after converting the property name to an environment variable name
            value = System.getenv(toEnvironmentVariableName(propertyName));
        }
        return value;
    }

    /**
     * Convert a property name to the environment variable name it conventionally
     * corresponds to: {@code dbHost} becomes {@code DB_HOST}. The word boundaries
     * of camel case become underscores, as do dots and dashes, and the result is
     * upper cased.
     *
     * <p>Public because a {@code .env} file stands in for the environment, and
     * has to resolve names the same way this does or the two would disagree
     * about what {@code dbHost} is called.
     *
     * @param propertyName
     * the property name to convert
     * @return
     * the conventional environment variable name for it
     */
    public static String toEnvironmentVariableName(String propertyName) {
        return propertyName
            .replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[\\.-]", "_")
            .toUpperCase();
    }


    //
    // private helper classes
    //

    private static final class EnvironmentProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        private EnvironmentProperties(Set<String> propertyNames) {
            propertyNames.stream()
                .forEach(name -> {
                    String value = getEnvironmentVariable(name);
                    if (value != null) {
                        map.put(name, value);
                    }
                });
        }

        @Override public String get(Object key) { return key instanceof String name ? map.get(name) : null; }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Set<String> keySet() { return Set.copyOf(map.keySet()); }
        @Override public Collection<String> values() { return List.copyOf(map.values()); }
        @Override public Set<Entry<String, String>> entrySet() { return Set.copyOf(map.entrySet()); }
        // other methods are not implemented, as they are not needed
        @Override public String put(String key, String value) { throw new UnsupportedOperationException(); }
        @Override public String remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends String, ? extends String> m) {
            throw new UnsupportedOperationException();
        }
    }
}
