package net.rabbitware.config.plugin.yaml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

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
public class YamlPlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(YamlPlugin.class);
    private String sourceName;
    private String location;
    private boolean resolveMergeKeys = true; // default is true

    public YamlPlugin() {
        logger.info("YAML plugin instantiated");
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
        return Set.of("location");
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of("resolveMergeKeys"); // default is true
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // set and validate required properties
        location = properties.get("location");
        if (location == null) {
            throw new Exception("missing required property: location");
        }
        String resolveMergeKeysStr = properties.get("resolveMergeKeys");
        if (resolveMergeKeysStr != null) {
            if (
                resolveMergeKeysStr.equalsIgnoreCase("true")
                || resolveMergeKeysStr.equalsIgnoreCase("yes")
                || resolveMergeKeysStr.equalsIgnoreCase("on")
                || resolveMergeKeysStr.equalsIgnoreCase("1")
            ) {
                resolveMergeKeys = true;
            } else if (
                resolveMergeKeysStr.equalsIgnoreCase("false")
                || resolveMergeKeysStr.equalsIgnoreCase("no")
                || resolveMergeKeysStr.equalsIgnoreCase("off")
                || resolveMergeKeysStr.equalsIgnoreCase("0")
            ) {
                resolveMergeKeys = false;
            } else {
                throw new Exception(
                    "invalid value for property `resolveMergeKeys` in YAML plugin: " + resolveMergeKeysStr
                );
            }
        }
        logger.info("setting plugin properties: location={}", location);
        if (!SimpleConfigSourcePlugin.isSupportedLocation(location)) {
            throw new Exception("unsupported location: " + location);
        }
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        // load the YAML content from the specified source (as a String)
        String sourceContent = SimpleConfigSourcePlugin.loadResource(location);
        // parse the YAML content and flatten it into a map of properties
        LoadSettings settings = LoadSettings.builder().setLabel("rwConfig YAML plugin").build();
        Load load = new Load(settings);
        Object yaml = load.loadFromString(sourceContent);
        if (resolveMergeKeys) {
            yaml = resolveMerges(yaml);
        }
        logger.info("loaded YAML content from source: {}", sourceName);
        Map<String, String> properties = new HashMap<>();
        getContents("", yaml, properties);
        logger.info("loaded {} properties from YAML source: {}", properties.size(), sourceName);
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
                // treat uniform primitive arrays as a list
                if (arrayIsPrimitiveAndUniform(list)) { // treat as a list
                    StringBuilder sb = new StringBuilder();
                    for (int index = 0; index < list.size(); index++) {
                        if (index > 0) {
                            sb.append(",");
                        }
                        sb.append(list.get(index));
                    }
                    add(map, prefix, sb.toString());
                } else { // treat as indexed objects
                    for (int index = 0; index < list.size(); index++) {
                        Object value = list.get(index);
                        getContents(prefix + "\\" + index, value, map);
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


    // returns true if the given JSONArray contains only primitive elements of
    // the same type
    private boolean arrayIsPrimitiveAndUniform(List<?> a) {
        int hasString = 0;
        int hasIntegers = 0;
        int hasFloats = 0;
        int hasBooleans = 0;
        for (int i = 0; i < a.size(); i++) {
            switch (a.get(i)) {
                case String s -> hasString = 1;
                case Boolean b -> hasBooleans = 1;
                case Number n -> {
                    String number = n.toString();
                    if (number.matches("[+-]?\\d+")) {
                        hasIntegers = 1;
                    } else if (number.matches("[+-]?(?:\\d+\\.\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?|[+-]?\\d+[eE][+-]?\\d+")) {
                        hasFloats = 1;
                    } else {
                        return false;
                    }
                }
                case null -> hasString = 1; // treat null as a string for uniformity
                default -> {
                    return false;
                }
            }
        }
        return hasString + hasIntegers + hasFloats + hasBooleans <= 1;
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
