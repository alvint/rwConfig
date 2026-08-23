package net.rabbitware.config.plugin.hocon;
import java.util.HashMap;
import java.util.Map;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;

/**
 * A simple HOCON plugin implementation. It leverages Typesafe Config to read
 * HOCON files and convert them into a flat map of properties. HOCON is a
 * superset of JSON, and is flattened by the same rules as the JSON plugin.
 * <p>
 * The plugin requires one property to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.location} - the location of the source.
 * </li>
 * </ul>
 */
public class HoconPlugin extends LocationBasedConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(HoconPlugin.class);

    public HoconPlugin() {
        logger.info("HOCON plugin instantiated");
    }


    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading HOCON source from location: {}", getLocation());
        String sourceContent = LocationBasedConfigSourcePlugin.loadResource(getLocation());
        // parse the HOCON content and flatten it into a map of properties
        Config config = ConfigFactory.parseString(sourceContent);
        config = config.resolve();
        Map<String, String> properties = new HashMap<>();
        getContents("", config.root(), properties);
        logger.info("loaded {} properties from HOCON source: {}", properties.size(), getSourceName());
        return properties;
    }

    // WARNING: this method is recursive and may throw a StackOverflowError for
    // deeply nested HOCON structures
    private void getContents(String prefix, ConfigValue value, Map<String, String> map) {
        switch (value) {
            case ConfigObject object -> {
                for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
                    String key = entry.getKey();
                    // escape backslashes in keys
                    key = key.replaceAll("\\\\", "\\\\\\\\");
                    // rename empty keys to `empty\key`
                    if (key.isEmpty()) {
                        logger.warn("renaming empty key at prefix `{}` to `empty\\key`", prefix);
                        key = "empty\\key";
                    }
                    getContents(prefix.isEmpty() ? key : prefix + "\\" + key, entry.getValue(), map);
                }
            }
            case ConfigList list -> {
                // treat uniform primitive arrays as a list
                if (arrayIsPrimitiveAndUniform(list)) { // treat as a list
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            sb.append(",");
                        }
                        sb.append(String.valueOf(list.get(i).unwrapped()));
                    }
                    add(map, prefix, sb.toString());
                } else { // treat as indexed objects
                    for (int i = 0; i < list.size(); i++) {
                        getContents(prefix.isEmpty() ? String.valueOf(i) : prefix + "\\" + i, list.get(i), map);
                    }
                }
            }
            default -> {
                switch (value.valueType()) {
                    case STRING, NUMBER, BOOLEAN, NULL -> add(map, prefix, value.unwrapped());
                    default -> logger.warn("unsupported value type at prefix `{}`: {}", prefix, value.valueType());
                }
            }
        }
    }


    // returns true if the given list contains only primitive elements of the
    // same type
    private boolean arrayIsPrimitiveAndUniform(ConfigList a) {
        int hasStrings = 0;
        int hasIntegers = 0;
        int hasFloats = 0;
        int hasBooleans = 0;
        for (int i = 0; i < a.size(); i++) {
            ConfigValue value = a.get(i);
            switch (value.valueType()) {
                case STRING -> hasStrings = 1;
                case BOOLEAN -> hasBooleans = 1;
                case NUMBER -> {
                    String number = value.unwrapped().toString();
                    if (number.matches("[+-]?\\d+")) {
                        hasIntegers = 1;
                    } else if (number.matches("[+-]?(?:\\d+\\.\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?|[+-]?\\d+[eE][+-]?\\d+")) {
                        hasFloats = 1;
                    } else {
                        return false;
                    }
                }
                case NULL -> hasStrings = 1; // treat null as a string for uniformity
                case OBJECT, LIST -> {
                    return false; // non-primitive object found
                }
            }
        }
        return hasStrings + hasIntegers + hasFloats + hasBooleans <= 1;
    }

    private void add(Map<String, String> map, String key, Object value) {
        if (map.containsKey(key)) {
            logger.error("REPORT THIS BUG! DATA LOSS! overwriting existing property: {} with value: {}", key, value);
        }
        map.put(key, String.valueOf(value));
    }
}
