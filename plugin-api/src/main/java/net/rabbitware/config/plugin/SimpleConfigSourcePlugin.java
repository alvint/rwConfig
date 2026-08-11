package net.rabbitware.config.plugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A simple plugin interface that defines the basic contract for plugins.
 * 
 * This interface will likely be supplanted in the future by a more advanced
 * plugin framework that includes more features. For now, it provides a minimal
 * set of methods that plugins can implement to hopefully get the job done.
 * 
 * <h4>How it works:</h4>
 * <ul>
 * <li>
 * The plugin is instantiated by the config library during its initialization.
 * The config library will use the plugin class's no-argument constructor to
 * create an instance of the plugin.
 * </li>
 * <li>
 * The config library will call the {@link #getPluginVersion()} method to
 * retrieve the version of the plugin.
 * </li>
 * <li>
 * The config library will call the {@link #setSourceName(String)} method to
 * set the source name for the plugin. This corresponds to the source name used
 * in the {@code rwconfig} file.
 * </li>
 * <li>
 * The config library will call the {@link #getRequiredPluginPropertyNames()}
 * and {@link #getOptionalPluginPropertyNames()} methods to retrieve the list of
 * required and optional properties that the plugin needs to function.
 * </li>
 * <li>
 * The config library will call the {@link #setPluginProperties(Map)} method to
 * to provide the plugin with its configuration properties. The plugin should
 * validate the properties and throw an exception if the properties as set are
 * not able to configure the plugin correctly.
 * </li>
 * <li>
 * The config library will call the {@link #getConfigSourceProperties()} method
 * to retrieve the configuration source properties from the plugin. The plugin
 * should return a map of property names and values that represent the source's
 * properties. If the plugin is unable to retrieve the properties, it should
 * throw an exception.
 * </li>
 * <li>
 * The config library may call the {@link #isChangeDetectionSupported()} method
 * to determine if the plugin supports change detection.
 * </li>
 * <li>
 * If change detection is supported, the config library may call the
 * {@link #addChangeListener(ChangeListener)} method to register a change
 * listener with the plugin. The plugin should notify the listener of changes
 * to the configuration source properties by calling the listener's
 * {@link ChangeListener#onChange(ChangeEvent)} method.
 * </li>
 * </ul>
 */
public interface SimpleConfigSourcePlugin {
    public static String loadFile(String sourceType, String path) throws Exception {
        switch (sourceType) {
            case "file" -> {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            }
            case "classpath" -> {
                var resource = Thread.currentThread().getContextClassLoader().getResource(path);
                if (resource == null) {
                    throw new IllegalArgumentException("classpath resource not found: " + path);
                }
                return Files.readString(Path.of(resource.toURI()), StandardCharsets.UTF_8);
            }
            case "url" -> {
                return Files.readString(Path.of(java.net.URI.create(path)), StandardCharsets.UTF_8);
            }
            default -> {
                throw new IllegalArgumentException("unsupported source type: " + sourceType);
            }
        }
    }

    public static boolean isSupportedSourceType(String sourceType) {
        return switch (sourceType) {
            case "file", "classpath", "url" -> true;
            default -> false;
        };
    }

    /**
     * Return the version of the plugin in the format "major[.minor[.patch]]"
     * 
     * @return
     * the version of the plugin
     */
    public String getPluginVersion();

    /**
     * Set the source name for the plugin. This corresponds to the source name
     * used in the {@code rwconfig} file.
     */
    public void setSourceName(String name);

    /**
     * Return {@code true} if the plugin supports change detection.
     * 
     * @return
     * {@code true} if change detection is supported, {@code false} otherwise
     */
    public boolean isChangeDetectionSupported();

    /**
     * Add a change listener to the plugin.
     * 
     * @param listener
     * the change listener to add
     */
    public void addChangeListener(ChangeListener listener);

    /**
     * Return the list of required plugin property names. Each property will be
     * retrieved from the {@code rwconfig} file using the key
     * {@code config.<sourceName>.propertyName}. If a key is not found, the
     * config library will throw an exception and initialization will fail.
     * 
     * @return
     * the list of required plugin property names
     */
    public Set<String> getRequiredPluginPropertyNames();

    /**
     * Return the list of optional plugin property names. Each property will be
     * retrieved from the {@code rwconfig} file using the key
     * {@code config.<sourceName>.propertyName}. If a key is not found, the
     * value will be set to {@code null} and the plugin will continue to
     * initialize.
     * 
     * @return
     * the list of optional plugin property names
     */
    public Set<String> getOptionalPluginPropertyNames();

    /**
     * Sets the plugin properties. This is a Map of property names and values
     * that the plugin can use to configure itself. The plugin should validate
     * the properties and throw an exception if the properties as set are not
     * able to configure the plugin correctly.
     * 
     * @param properties
     * the plugin properties to set
     * @throws Exception
     * if an error occurs while setting the properties
     */
    public void setPluginProperties(Map<String, String> properties) throws Exception;

    /**
     * Retrieve the configuration source properties.
     * 
     * @return
     * the configuration source properties
     * @throws Exception
     * if an error occurs while retrieving the properties from the configuration
     * source
     */
    public Map<String, String> getConfigSourceProperties() throws Exception;

    public static interface ChangeListener {
        public void onChange(ChangeEvent event);
    }

    public static record ChangeEvent(
        Instant timestamp,
        String source,
        String details,
        List<ChangedProperty> changedProperties
    ){}

    public static record ChangedProperty(
        String propertyName,
        String oldValue,
        String newValue
    ) {}
}
