package net.rabbitware.config.plugin.api;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    /**
     * Load the content of a resource from the specified location. The location
     * must start with one of the following prefixes:
     * <ul>
     * <li>
     * {@code classpath:} - Load the resource from the classpath.
     * </li>
     * <li>
     * {@code file:} - Load the resource from the filesystem. The location can
     * be an absolute path or a relative path. If it is a relative path, it will
     * be resolved against the current working directory.
     * </li>
     * <li>
     * {@code jar:} - Load the resource from a JAR file. The location must be a
     * valid JAR URL, such as {@code jar:file:/path/to/jar!/path/inside/jar}.
     * The JAR file must be accessible and readable by the application.
     * </li>
     * <li>
     * {@code http:} - Load the resource from an HTTP URL.
     * </li>
     * <li>
     * {@code https:} - Load the resource from an HTTPS URL.
     * </li>
     * </ul>
     * If the location does not start with one of these prefixes, an
     * {@link IllegalArgumentException} will be thrown.
     * <p>
     * The content is returned as a String.
     *
     * @param location
     * the location of the resource to load
     * @return
     * the content of the resource as a String
     * @throws Exception
     * if an error occurs while loading the resource
     */
    public static String loadResource(String location) throws Exception {
        URL url;
        // check if the location has a `classpath:` prefix - if so, remove it
        // and load the resource from the classpath
        if (location.startsWith("classpath:")) {
            location = location.substring("classpath:".length());
            url = Thread.currentThread().getContextClassLoader().getResource(location);
            if (url == null) {
                throw new IllegalArgumentException("classpath resource not found: " + location);
            }
        } else if (location.startsWith("file:")) { // not a classpath resource - check if it's a file URL
            // convert the file URL to an absolute file path
            URI uri = URI.create(location);
            Path path = Path.of(uri.getSchemeSpecificPart()).toAbsolutePath();
            URI absoluteUri = path.toUri();
            url = absoluteUri.toURL();
        } else { // anything else
            // check if it's a URL with a scheme (including file URLs)
            URI uri = URI.create(location);
            if (uri.isAbsolute()) { // a URL we can load directly
                url = uri.toURL();
            } else { // not a URL
                throw new IllegalArgumentException("location is not a valid URL: " + location);
            }
        }
        return new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Return {@code true} if the specified location to a resource is supported.
     * <p>
     * Supported locations are:
     * <ul>
     * <li>
     * {@code classpath:} - Load the resource from the classpath.
     * </li>
     * <li>
     * {@code file:} - Load the resource from the filesystem.
     * </li>
     * <li>
     * {@code jar:} - Load the resource from a JAR file.
     * </li>
     * <li>
     * {@code http:} - Load the resource from an HTTP URL.
     * </li>
     * <li>
     * {@code https:} - Load the resource from an HTTPS URL.
     * </li>
     * </ul>
     * 
     * @param location
     * the location to check
     * @return
     * {@code true} if the specified location to a resource is supported,
     * {@code false} otherwise
     */
    public static boolean isSupportedLocation(String location) {
        location = location.toLowerCase().trim();
        return location.startsWith("classpath:")
            || location.startsWith("file:")
            || location.startsWith("jar:")
            || location.startsWith("http:")
            || location.startsWith("https:");
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
