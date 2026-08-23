package net.rabbitware.config.plugin.api;
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
 * <li>
 * The config library will call the {@link #getConfigSourceProperties()} method
 * to retrieve the configuration source properties from the plugin. The plugin
 * should return a map of property names and values that represent the source's
 * properties. If the plugin is unable to retrieve the properties, it should
 * throw an exception.
 * </li>
 * </ul>
 */
public interface SimpleConfigSourcePlugin {
    /**
     * The version of this plugin API, read from the jar it was built into.
     *
     * <p>Kept here rather than written into each plugin, because a version
     * spelled out in source drifts the moment the project's version changes and
     * nothing fails to remind you.
     */
    String API_VERSION = loadApiVersion();

    /**
     * Return the version of the plugin in the format "major[.minor[.patch]]".
     *
     * <p>A plugin that ships with rwConfig is versioned with it, so the default
     * is right for all of them. A plugin released on its own schedule should
     * override this and report its own version.
     *
     * @return
     * the version of the plugin
     */
    default String getPluginVersion() {
        return API_VERSION;
    }

    /**
     * Read the version Maven filtered into {@code version.properties} at build
     * time. Falls back rather than throwing: a missing version is not a reason
     * for a plugin to be unloadable, and this runs during class initialization,
     * where a failure would surface far from its cause.
     */
    private static String loadApiVersion() {
        try (java.io.InputStream in =
                SimpleConfigSourcePlugin.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "0.0.0";
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(in);
            return properties.getProperty("version", "0.0.0");
        } catch (java.io.IOException e) {
            return "0.0.0";
        }
    }

    /**
     * Set the source name for the plugin. This corresponds to the source name
     * used in the {@code rwconfig} file.
     */
    public void setSourceName(String sourceName);

    /**
     * Get the source name for the plugin. This corresponds to the source name
     * used in the {@code rwconfig} file.
     * 
     * @return
     * the source name of the plugin
     */
    public String getSourceName();

    /**
     * Return {@code true} if the plugin supports change detection.
     * 
     * @return
     * {@code true} if change detection is supported, {@code false} otherwise
     */
    public boolean isChangeDetectionSupported();

    /**
     * Start the change detection process for the plugin. This method should be
     * called before checking for changes using {@link #isChanged()}.
     * 
     * @throws Exception
     * if an error occurs while starting change detection
     */
    public void startChangeDetection() throws Exception;

    /**
     * Stop the change detection process for the plugin. This method should be
     * called when change detection is no longer needed.
     * 
     * @throws Exception
     * if an error occurs while stopping change detection
     */
    public void stopChangeDetection() throws Exception;

    /**
     * Return {@code true} if the configuration source has changed since the
     * last time it was checked.
     * 
     * @return
     * {@code true} if the configuration source has changed
     */
    public boolean isChanged() throws Exception;

    /**
     * Return the list of required plugin property names. Each property will be
     * retrieved from the {@code rwconfig} file using the key
     * {@code rwc.<sourceName>.propertyName}. If a key is not found, the
     * config library will throw an exception and initialization will fail.
     * 
     * @return
     * the list of required plugin property names
     */
    public Set<String> getRequiredPluginPropertyNames();

    /**
     * Return the list of optional plugin property names. Each property will be
     * retrieved from the {@code rwconfig} file using the key
     * {@code rwc.<sourceName>.propertyName}. If a key is not found, the
     * value will be set to {@code null} and the plugin will continue to
     * initialize.
     * 
     * @return
     * the list of optional plugin property names
     */
    public Set<String> getOptionalPluginPropertyNames();

    /**
     * Set the plugin properties. This is a Map of property names and values
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
}
