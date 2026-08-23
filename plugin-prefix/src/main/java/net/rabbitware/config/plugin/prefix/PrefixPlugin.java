package net.rabbitware.config.plugin.prefix;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Properties;
import java.util.Set;
import java.io.StringReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;

/**
 * A simple prefix plugin implementation. It will prefix the name of the source
 * (as set in the {@code rwconfig} file) to the name of each property in the
 * source.
 * <p>
 * It requires two properties to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.mediaType} - the media type of the source.
 * Currently, the only supported media type is {@code properties}.
 * </li>
 * <li>
 * {@code rwc.<sourceName>.location} - the location of the source.
 * </li>
 * </ul>
 */
public class PrefixPlugin extends LocationBasedConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(PrefixPlugin.class);
    private String mediaType;

    public PrefixPlugin() {
        logger.info("prefix plugin instantiated");
    } 


    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("location", "mediaType");
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // set and validate `location` property
        super.setPluginProperties(properties);
        // set and validate `mediaType` property
        mediaType = properties.get("mediaType");
        if (mediaType == null || mediaType.isBlank()) {
            throw new Exception("missing required property: mediaType");
        }
        if (!mediaType.equals("properties")) {
            throw new Exception("unsupported mediaType: " + mediaType);
        }
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading resource from location: {}", getLocation());
        String sourceContent = loadLocation();
        Properties properties = new Properties();
        try (StringReader reader = new StringReader(sourceContent)) {
            properties.load(reader);
            logger.info("config source `{}` loaded from `{}`", getSourceName(), getLocation());
        }
        return properties.entrySet().stream()
            .collect(Collectors.toMap(
                e -> getSourceName().concat(".").concat(e.getKey().toString()),
                e -> e.getValue().toString()
            ));
    }
}
