package net.rabbitware.config.plugin.prefix;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Properties;
import java.util.Set;
import java.io.StringReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

/**
 * A simple prefix plugin implementation. It will prefix the name of the source
 * (as set in the {@code rwconfig} file) to the name of each property in the
 * source.
 * <p>
 * It requires two properties to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code config.<sourceName>.mediaType} - the media type of the source.
 * Currently, the only supported media type is {@code properties}.
 * </li>
 * <li>
 * {@code config.<sourceName>.location} - the location of the source.
 * </li>
 * </ul>
 */
public class PrefixPlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(PrefixPlugin.class);
    private String sourceName;
    private String mediaType;
    private String location;

    public PrefixPlugin() {
        logger.info("prefix plugin instantiated");
    } 

    @Override
    public String getPluginVersion() {
        return "1.0.0";
    }

    @Override
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
        logger.info("source name set to: {}", this.sourceName);
    }

    @Override
    public boolean isChangeDetectionSupported() {
        return false;
    }

    @Override
    public void addChangeListener(SimpleConfigSourcePlugin.ChangeListener listener) {
        // not supported
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("mediaType", "location");
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of(); // no optional properties
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // set and validate required properties
        mediaType = properties.get("mediaType");
        if (mediaType == null || mediaType.isBlank()) {
            throw new Exception("missing required property: mediaType");
        }
        location = properties.get("location");
        if (location == null || location.isBlank()) {
            throw new Exception("missing required property: location");
        }
        logger.info("setting properties: mediaType={}, location={}", mediaType, location);
        if (!SimpleConfigSourcePlugin.isSupportedLocation(location)) {
            throw new Exception("unsupported location: " + location);
        }
        if (!mediaType.equals("properties")) {
            throw new Exception("unsupported mediaType: " + mediaType);
        }
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        Properties properties = new Properties();
        String sourceContent = SimpleConfigSourcePlugin.loadResource(location);
        try (StringReader reader = new StringReader(sourceContent)) {
            properties.load(reader);
            logger.debug("config source `{}` loaded from `{}`", sourceName, location);
        }
        return properties.entrySet().stream()
            .collect(Collectors.toMap(
                e -> sourceName.concat(".").concat(e.getKey().toString()),
                e -> e.getValue().toString()
            ));
    }
}
