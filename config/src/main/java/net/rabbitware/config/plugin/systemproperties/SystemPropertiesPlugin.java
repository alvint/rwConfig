package net.rabbitware.config.plugin.systemproperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemPropertiesPlugin implements SimpleConfigSourcePlugin {
    private static Logger logger = LoggerFactory.getLogger(SystemPropertiesPlugin.class);

    private String sourceName;
    private final Set<String> propertyNames;

    public SystemPropertiesPlugin(Set<String> propertyNames) {
        this.propertyNames = propertyNames;
        logger.info("system properties plugin instantiated");
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

    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        Map<String, String> configSourceProperties = new SystemProperties(propertyNames);
        logger.info("config source `{}` loaded from system properties", sourceName);
        return configSourceProperties;
    }


    //
    // public helper methods
    //

    /**
     * Retrieve the value of the system property corresponding to the given
     * property name.
     *
     * @param propertyName
     * the name of the system property to retrieve
     * @return
     * the value of the system property, or {@code null} if not found
     */
    public static String getSystemProperty(String propertyName) {
        return System.getProperty(propertyName);
    }


    //
    // private helper classes
    //

    private static final class SystemProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        private SystemProperties(Set<String> propertyNames) {
            propertyNames.stream()
                .forEach(name -> {
                    String value = getSystemProperty(name);
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
