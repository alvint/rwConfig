package net.rabbitware.config.plugin.prefix;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Properties;
import java.util.Set;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.SimpleConfigSourcePlugin;

/**
 * A simple prefix plugin implementation. It will prefix the name of the source
 * (as set in the {@code rwconfig} file) to the name of each property in the
 * source.
 * <p>
 * It requires two properties to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code config.<sourceName>.sourceType} - the type of the source. Currently,
 * the only supported source type is {@code propertiesFile}.
 * </li>
 * <li>
 * {@code config.<sourceName>.path} - the path to the source.
 * </li>
 * </ul>
 */
public class PrefixPlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(PrefixPlugin.class);
    private String sourceName;
    private String sourceType;
    private Path path;

    public PrefixPlugin() {
        logger.info("PrefixPlugin instantiated");
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
        return Set.of("sourceType", "path");
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of(); // no optional properties
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // set and validate required properties
        sourceType = properties.get("sourceType");
        if (sourceType == null) {
            throw new Exception("missing required property: sourceType");
        }
        if (!sourceType.equalsIgnoreCase("propertiesFile")) {
            throw new Exception("unsupported sourceType: " + sourceType);
        }
        String pathString = properties.get("path");
        if (pathString == null) {
            throw new Exception("missing required property: path");
        }
        path = Path.of(pathString);
        if (!path.toFile().exists()) {
            throw new Exception("path does not exist: " + path);
        }
        if (!path.toFile().isFile()) {
            throw new Exception("path is not a file: " + path);
        }
        if (!path.toFile().canRead()) {
            throw new Exception("path is not readable: " + path);
        }
        logger.info("Plugin properties set: sourceType={}, path={}", sourceType, path);
    }

    @Override
    public Map<String, String> getConfigSourceProperties() {
        Properties properties = new Properties();
        switch (sourceType) {
            case "propertiesFile" -> {
                try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
                    properties.load(reader);
                    logger.debug("config source `{}` loaded from file: {}", sourceName, path);
                } catch (IOException e) {
                    throw new RuntimeException(
                        "error reading config source file for `" + sourceName + "`: " + path, e
                    );
                }
            }
            default -> {
                throw new RuntimeException("unsupported sourceType: " + sourceType);
            }
        }
        return properties.entrySet().stream()
            .collect(Collectors.toMap(
                e -> sourceName.concat(".").concat(e.getKey().toString()),
                e -> e.getValue().toString()
            ));
    }
    
}
