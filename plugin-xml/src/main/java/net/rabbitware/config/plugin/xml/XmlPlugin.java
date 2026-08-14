package net.rabbitware.config.plugin.xml;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.json.XML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

/**
 * A simple XML plugin implementation. It leverages the {@code org.json}
 * library to read XML files and convert them into a flat map of properties.
 * <p>
 * The plugin requires one property to be set in the {@code rwconfig} file:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.location} - the location of the source.
 * </li>
 * </ul>
 */
public class XmlPlugin implements SimpleConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(XmlPlugin.class);
    private String sourceName;
    private String location;

    public XmlPlugin() {
        logger.info("XML plugin instantiated");
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
        // set and validate required properties
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
        // load the XML content from the specified source (as a String)
        String sourceContent = SimpleConfigSourcePlugin.loadResource(location);
        // parse the XML content into a JSON object and flatten it into a map of properties
        JSONObject json = XML.toJSONObject(sourceContent);
        logger.info("loaded XML content from source: {}", sourceName);
        Map<String, String> properties = new HashMap<>();
        getContents("", json, properties);
        logger.info("loaded {} properties from XML source: {}", properties.size(), sourceName);
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
                if (
                    arrayContainsOnly(jsonArray, Byte.class, Short.class, Integer.class, Long.class)
                    || arrayContainsOnly(jsonArray, Float.class, Double.class)
                    || arrayContainsOnly(jsonArray, String.class)
                    || arrayContainsOnly(jsonArray, Boolean.class)
                ) {
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

    // returns true if the given JSONArray contains only elements of the
    // specified class
    private boolean arrayContainsOnly(org.json.JSONArray a, Class<?> c1) {
        return arrayContainsOnly(a, c1, null, null, null);
    }
        
    // returns true if the given JSONArray contains only elements of the
    // specified classes
    private boolean arrayContainsOnly(org.json.JSONArray a, Class<?> c1, Class<?> c2) {
        return arrayContainsOnly(a, c1, c2, null, null);
    }

    // returns true if the given JSONArray contains only elements of the
    // specified classes
    private boolean arrayContainsOnly(org.json.JSONArray a, Class<?> c1, Class<?> c2, Class<?> c3, Class<?> c4) {
        if (c2 == null) {
            c2 = c1;
        }
        if (c3 == null) {
            c3 = c1;
        }
        if (c4 == null) {
            c4 = c1;
        }
        if (a.length() == 0) {
            return true; // empty array is considered homogeneous
        }
        for (int i = 0; i < a.length(); i++) {
            Object element = a.get(i);
            if (
                !(c1.isInstance(element) || c2.isInstance(element) || c3.isInstance(element) || c4.isInstance(element))
            ) {
                return false;
            }
        }
        return true;
    }

    private void add(Map<String, String> map, String key, Object value) {
        if (map.containsKey(key)) {
            logger.error("REPORT THIS BUG! DATA LOSS! overwriting existing property: {} with value: {}", key, value);
        }
        map.put(key, String.valueOf(value));
    }
}
