package net.rabbitware.config.plugin.json;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

/**
 * A simple JSON plugin implementation. It leverages the {@code org.json}
 * library to read JSON files and convert them into a flat map of properties.
 * <p>
 * The plugin requires one property to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.location} - the location of the source.
 * </li>
 * </ul>
 */
public class JsonPlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(JsonPlugin.class);
    private String sourceName;
    private String location;

    public JsonPlugin() {
        logger.info("JSON plugin instantiated");
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
        return Set.of(); // no optional properties
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        location = properties.get("location");
        if (location == null) {
            throw new Exception("missing required property: location");
        }
        logger.info("setting plugin properties: location={}", location);
        if (!SimpleConfigSourcePlugin.isSupportedLocation(location)) {
            throw new Exception("unsupported location: " + location);
        }
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        // load the JSON content from the specified source (as a String)
        String sourceContent = SimpleConfigSourcePlugin.loadResource(location);
        // wrap in braces if not already a JSON object
        if (!sourceContent.trim().startsWith("{")) {
            sourceContent = "{ \"unnamed\": " + sourceContent + " }";
        }
        // parse the JSON content and flatten it into a map of properties
        JSONObject json = new JSONObject(sourceContent);
        Map<String, String> properties = new HashMap<>();
        getContents("", json, properties);
        logger.info("loaded {} properties from JSON source: {}", properties.size(), sourceName);
        return properties;
    }   

    // WARNING: this method is recursive and may throw a StackOverflowError for
    // deeply nested JSON structures
    private void getContents(String prefix, Object object, Map<String, String> map) {
        switch (object) {
            case JSONObject jsonObject -> {
                for (String key : jsonObject.keySet()) {
                    Object value = jsonObject.get(key);
                    // escape backslashes in keys
                    key = key.replaceAll("\\\\", "\\\\\\\\");
                    // rename empty keys to `empty\key`
                    if (key.isEmpty()) {
                        logger.warn("renaming empty key at prefix `{}` to `empty\\key`", prefix);
                        key = "empty\\key";
                    }
                    getContents(prefix.isEmpty() ? key : prefix + "\\" + key, value, map);
                }
            }
            case org.json.JSONArray jsonArray -> {
                // treat uniform primitive arrays as a list
                if (arrayIsPrimitiveAndUniform(jsonArray)) {
                    // treat as a list
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        if (i > 0) {
                            sb.append(",");
                        }
                        sb.append(String.valueOf(jsonArray.get(i)));
                    }
                    add(map, prefix, sb.toString());
                } else {
                    // treat as indexed objects
                    for (int i = 0; i < jsonArray.length(); i++) {
                        Object value = jsonArray.get(i);
                        getContents(prefix + "\\" + i, value, map);
                    }
                }
            }
            case String strValue -> add(map, prefix, strValue);
            case Number numValue -> add(map, prefix, numValue.toString());
            case Boolean boolValue -> add(map, prefix, Boolean.toString(boolValue));
            case Object o  -> {
                if (o == JSONObject.NULL) {
                    add(map, prefix, "null");
                } else {
                    logger.warn("unsupported JSON value type: {} for key: {}", o.getClass().getSimpleName(), prefix);
                }
            }
        }
    }


    // returns true if the given JSONArray contains only primitive elements of
    // the same type
    private boolean arrayIsPrimitiveAndUniform(org.json.JSONArray a) {
        int hasString = 0;
        int hasIntegers = 0;
        int hasFloats = 0;
        int hasBooleans = 0;
        for (int i = 0; i < a.length(); i++) {
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
                case Object o -> {
                    if (o == JSONObject.NULL) {
                        hasString = 1; // treat null as a string for uniformity
                    } else {
                        return false; // non-primitive object found
                    }
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
}
