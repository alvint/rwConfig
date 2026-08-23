package net.rabbitware.config.plugin.api;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LocationBasedConfigSourcePlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(LocationBasedConfigSourcePlugin.class);

    public static final int URL_LOCATION_POLL_INTERVAL_MILLIS = 30_000;

    // change detection stuff
    /**
     * Whether change detection is running. A flag of its own rather than a null
     * check on one of the fields below: only the file path uses a
     * {@link WatchService}, so testing that would report an HTTP source as never
     * started, and a closed one as still running.
     */
    private boolean changeDetectionStarted;

    private WatchService watchService; // used to watch for changes in file-based resources
    private Path filePath; // used to watch for changes in file-based resources
    private URL url; // used to watch for changes in HTTP(S)-based resources
    private long lastModifiedTime; // used to watch for changes in HTTP(S)-based resources
    private long lastChecked; // used to watch for changes in HTTP(S)-based resources

    /**
     * How long {@link #loadResource(String)} will wait to connect to a remote
     * resource before giving up, in milliseconds.
     */
    public static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /**
     * How long {@link #loadResource(String)} will wait for a remote resource to
     * send data before giving up, in milliseconds. This is the timeout that
     * matters when a server accepts the connection but never answers.
     */
    public static final int READ_TIMEOUT_MILLIS = 10_000;

    /**
     * The interval at which `watchResource` will poll for changes in a non-file
     * resource, in milliseconds. File resources (including "jar:file:" URLs)
     * are watched using the filesystem's native change notification mechanism.
     */
    public static final int CHANGE_DETECTION_POLL_INTERVAL_MILLIS = 5_000;
    
    /**
     * The maximum number of consecutive errors allowed while checking for
     * changes before giving up.
     */
    public static final int CHANGE_DETECTION_MAX_CONSECUTIVE_ERRORS = 12;

    private String sourceName;
    private String location;

    @Override
    public void setSourceName(String sourceName) {
        logger.debug("setting source name: {}", sourceName);
        this.sourceName = sourceName;
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method returns a set containing the "location" property by default.
     * If you need additional required properties, subclasses should override
     * this method and include them in the returned set.
     *
     * @return
     * a set of required plugin property names
     */
    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("location");
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method returns an empty set by default. If you need any optional
     * properties, subclasses should override this method and include them in
     * the returned set.
     *
     * @return
     * a set of optional plugin property names
     */
    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of();
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method sets the `location` property. Subclasses should override this
     * method to handle the additional properties if needed, then call
     * `super.setPluginProperties(properties)` to ensure that the `location`
     * property is set.
     */
    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        logger.debug("setting plugin properties: {}", properties);
        location = properties.get("location");
        if (location == null || location.isBlank()) {
            throw new Exception("missing required property: location");
        }
        if (!LocationBasedConfigSourcePlugin.isSupportedLocation(location)) {
            throw new Exception("unsupported location: " + location);
        }
    }

    @Override
    public boolean isChangeDetectionSupported() {
        return location != null && (
            location.startsWith("file:")
            || location.startsWith("jar:file:")
            || location.startsWith("http:")
            || location.startsWith("https:")
        );
    }

    @Override
    public void startChangeDetection() throws Exception {
        if (changeDetectionStarted) {
            throw new IllegalStateException("change detection is already started");
        }
        if (location == null) {
            throw new IllegalStateException("location is not set");
        }
        logger.debug("starting change detection for location: {}", location);
        String resourceLocation = stripJarStuff(location);
        if (resourceLocation.startsWith("file:")) { // files can use WatchService
            watchService = FileSystems.getDefault().newWatchService();
            filePath = Path.of(resourceLocation.substring("file:".length())).toAbsolutePath();
            filePath.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        } else if (resourceLocation.startsWith("http:") || resourceLocation.startsWith("https:")) { // use timestamps
            url = URI.create(resourceLocation).toURL();
            URLConnection connection = url.openConnection();
            lastModifiedTime = connection.getLastModified();
        } else {
            throw new Exception("unsupported location: " + location);
        }
        changeDetectionStarted = true;
        logger.debug("change detection started");
    }

    @Override
    public void stopChangeDetection() throws Exception {
        if (!changeDetectionStarted) {
            throw new IllegalStateException("change detection is not started");
        }
        logger.debug("stopping change detection for location: {}", location);
        if (watchService != null) {
            watchService.close();
        }
        // Clear everything the started state was built from, so that starting
        // again begins from scratch rather than on a closed watch service.
        watchService = null;
        filePath = null;
        url = null;
        lastModifiedTime = 0;
        changeDetectionStarted = false;
        logger.debug("change detection stopped");
    }

    @Override
    public boolean isChanged() throws Exception {
        logger.trace("checking if location `{}` has changed", location);
        long currentTime = System.currentTimeMillis();
        if (watchService != null) { // it must be a file-based resource
            WatchKey key = watchService.poll();
            if (key == null) {
                logger.trace("no change events for location `{}`", location);
                return false; // no events to process
            }
            logger.trace("processing change events for location `{}`", location);
            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                Path fileName = (Path) event.context();
                if (fileName.equals(filePath.getFileName())) { // the file that we're interested in changed
                    logger.debug("file `{}` has changed for location `{}`", fileName, location);
                    changed = true;
                    break;
                }
            }
            // reset the key
            if (!key.reset()) { // directory is no longer accessible
                logger.debug("directory for location `{}` is no longer accessible", location);
                changed = true; // directory is no longer accessible - consider the resource changed
            }
            return changed;
        } else if (url != null) { // it must be an HTTP/HTTPS-based resource
            if (currentTime - lastChecked < URL_LOCATION_POLL_INTERVAL_MILLIS) {
                logger.trace("not enough time has passed since the last check for location `{}` - skipping check", location);
                return false; // not enough time has passed since the last check
            }
            logger.trace("checking HTTP/HTTPS resource at location `{}` for changes", location);
            URLConnection connection = url.openConnection();
            long urlModifiedTime = connection.getLastModified();
            if (urlModifiedTime > lastModifiedTime) {
                logger.debug("HTTP/HTTPS resource at location `{}` has changed", location);
                lastModifiedTime = urlModifiedTime;
                return true;
            } else {
                logger.trace("HTTP/HTTPS resource at location `{}` has not changed", location);
                lastChecked = currentTime;
                return false;
            }
        } else {
            throw new Exception("unsupported location: " + location);
        }
    }


    /**
     * Get the value of the `location` property.
     *
     * @return the location
     */
    protected String getLocation() {
        return location;
    }


    //
    // utility methods for loading resources
    //

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
        // Read through a connection rather than `url.openStream()` so that the
        // timeouts below apply. Without them, an `http:` source whose server
        // accepts the connection and then never answers would block startup
        // forever, with nothing logged. Protocols that have no notion of a
        // timeout (`classpath:`, `file:`, `jar:`) ignore these.
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        try (InputStream in = connection.getInputStream()) {
            // check if the resource is readable
            if (in == null) {
                throw new IllegalArgumentException("resource is not readable: " + location);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    public static boolean parseBoolean(String value) {
        if (value == null) {
            throw new IllegalArgumentException("invalid boolean value : null");
        } else if (
            value.equalsIgnoreCase("true")
            || value.equalsIgnoreCase("yes")
            || value.equalsIgnoreCase("on")
            || value.equalsIgnoreCase("1")
        ) {
            return true;
        } else if (
            value.equalsIgnoreCase("false")
            || value.equalsIgnoreCase("no")
            || value.equalsIgnoreCase("off")
            || value.equalsIgnoreCase("0")
        ) {
            return false;
        } else {
            throw new IllegalArgumentException("invalid boolean value: " + value);
        }
    }


    //
    // private helper methods
    //

    private static String stripJarStuff(String location) {
        if (location.startsWith("jar:")) {
            int endIndex = location.indexOf("!");
            if (endIndex == -1) {
                throw new IllegalArgumentException("Invalid JAR URL: " + location);
            }
            return location.substring("jar:".length(), endIndex);
        } else {
            return location;
        }
    }
}
