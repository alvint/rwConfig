package net.rabbitware.config.plugin.yaml;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.ConstructNode;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;
import net.rabbitware.config.plugin.api.ListValues;

/**
 * A simple YAML plugin implementation. It leverages the {@code eo-yaml}
 * library to read YAML files and convert them into a flat map of properties.
 * <p>
 * The plugin requires one property to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.location} - the location of the source.
 * </li>
 * </ul>
 */
public class YamlPlugin extends LocationBasedConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(YamlPlugin.class);

    /**
     * Builds a YAML float as a {@link BigDecimal}, so that it keeps every digit
     * and the scale it was written with - {@code 10.50} stays {@code 10.50}.
     * The library's own float constructor goes through a {@code double}, which
     * would turn {@code 1.00000000000000000001} into {@code 1.0} before the
     * value ever reached a {@code bigDecimal} property.
     * <p>
     * The special values are handled exactly as that constructor handles them.
     * It cannot simply be extended, because its package is not exported.
     */
    private static final ConstructNode EXACT_FLOAT = node -> {
        String value = ((ScalarNode) node).getValue();
        return switch (value) {
            case ".inf" -> Double.POSITIVE_INFINITY;
            case "-.inf" -> Double.NEGATIVE_INFINITY;
            case ".nan" -> Double.NaN;
            default -> new BigDecimal(value);
        };
    };
    private boolean resolveMergeKeys = true; // default is true

    public YamlPlugin() {
        logger.info("YAML plugin instantiated");
    }


    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        // add `resolveMergeKeys` to the location-based properties supplied by
        // the parent class
        Set<String> names = new HashSet<>(super.getOptionalPluginPropertyNames());
        names.add("resolveMergeKeys"); // default is true
        return names;
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // set and validate `location` property
        super.setPluginProperties(properties);
        // set and validate `resolveMergeKeys` property
        resolveMergeKeys = LocationBasedConfigSourcePlugin.parseBoolean(
            properties.getOrDefault("resolveMergeKeys", "true")
        );
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading resource from location: {}", getLocation());
        String sourceContent = loadLocation();
        // parse the YAML content and flatten it into a map of properties
        LoadSettings settings = LoadSettings.builder()
            .setLabel("rwConfig YAML plugin")
            .setTagConstructors(Map.of(Tag.FLOAT, EXACT_FLOAT))
            .build();
        Load load = new Load(settings);
        Object yaml = load.loadFromString(sourceContent);
        if (resolveMergeKeys) {
            yaml = resolveMerges(yaml);
        }
        logger.info("loaded YAML content from source: {}", getSourceName());
        Map<String, String> properties = new HashMap<>();
        getContents("", yaml, properties);
        logger.info("loaded {} properties from YAML source: {}", properties.size(), getSourceName());
        return properties;
    }   

    // WARNING: this method is recursive and may throw a StackOverflowError for
    // deeply nested YAML structures
    private void getContents(String prefix, Object yaml, Map<String, String> map) {
        switch (yaml) {
            case Map<?, ?> object -> {
                for (Map.Entry<?, ?> entry : object.entrySet()) {
                    String keyName = (String) entry.getKey();
                    if (keyName == null) { // rename null keys to `null\key`
                        logger.warn("renaming null key at prefix `{}` to `null\\key`", prefix);
                        keyName = "null\\key";
                    } else if (keyName.isEmpty()) { // rename empty keys to `empty\key`
                        logger.warn("renaming empty key at prefix `{}` to `empty\\key`", prefix);
                        keyName = "empty\\key";
                    } else { // escape backslashes in keys
                        keyName = keyName.replaceAll("\\\\", "\\\\\\\\");
                    }
                    getContents(prefix.isEmpty() ? keyName : prefix + "\\" + keyName, entry.getValue(), map);
                }
            }
            case List<?> list -> {
                if (ListValues.allAreValues(list, YamlPlugin::isValue)) { // one list value
                    add(map, prefix, ListValues.join(list, String::valueOf));
                } else { // indexed
                    for (int index = 0; index < list.size(); index++) {
                        getContents(prefix + "\\" + index, list.get(index), map);
                    }
                }
            }
            case String strValue -> add(map, prefix, strValue);
            case Number numValue -> add(map, prefix, numValue.toString());
            case Boolean boolValue -> add(map, prefix, Boolean.toString(boolValue));
            case null  -> {
                add(map, prefix, "null");
            }
            default -> {
                logger.warn("unsupported YAML node type at prefix `{}`: {}", prefix, yaml.getClass().getName());
            }
        }
    }


    // a value that can be one item of a list - anything else, including a
    // value tagged `!!binary` or `!!set`, cannot be written out as one
    private static boolean isValue(Object o) {
        return o == null || o instanceof String || o instanceof Number || o instanceof Boolean;
    }

    private void add(Map<String, String> map, String key, Object value) {
        if (map.containsKey(key)) {
            logger.error("REPORT THIS BUG! DATA LOSS! overwriting existing property: {} with value: {}", key, value);
        }
        map.put(key, String.valueOf(value));
    }

    /**
     * Hopefully this AI-generated code won't steal my credit card numbers...
     * <p>
     * Recursively resolves YAML merge keys (<<) in the given node.
     * <p>
     * This method handles both maps and lists, and it will recursively resolve
     * merges in all child nodes before resolving the current node's merge key.
     *
     * @param node
     * The YAML node to resolve merges for.
     * @return
     * The resolved node with all merges applied.
     */
    @SuppressWarnings("unchecked")
    static Object resolveMerges(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;

            // First, recursively resolve merges in all values (bottom-up)
            Map<String, Object> resolvedChildren = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                resolvedChildren.put(entry.getKey(), resolveMerges(entry.getValue()));
            }

            // Now handle this level's own merge key, if present
            Object mergeVal = resolvedChildren.remove("<<");
            if (mergeVal == null) {
                return resolvedChildren;
            }

            List<Map<String, Object>> toMerge = (mergeVal instanceof List)
                    ? (List<Map<String, Object>>) mergeVal
                    : List.of((Map<String, Object>) mergeVal);

            Map<String, Object> result = new LinkedHashMap<>();
            for (Map<String, Object> merged : toMerge) {
                result.putAll(merged); // earlier anchors in the list take precedence
            }
            result.putAll(resolvedChildren); // local keys always win over merged ones
            return result;

        } else if (node instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(resolveMerges(item));
            }
            return result;

        } else {
            return node; // scalar — nothing to do
        }
    }
}
