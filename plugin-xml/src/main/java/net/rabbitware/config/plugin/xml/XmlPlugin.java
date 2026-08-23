package net.rabbitware.config.plugin.xml;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.json.XML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;

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
public class XmlPlugin extends LocationBasedConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(XmlPlugin.class);

    public XmlPlugin() {
        logger.info("XML plugin instantiated");
    }


    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading resource from location: {}", getLocation());
        String sourceContent = loadLocation();
        // parse the XML content into a JSON object and flatten it into a map of properties
        JSONObject json = XML.toJSONObject(sourceContent);
        logger.info("loaded XML content from source: {}", getSourceName());
        Map<String, String> properties = new HashMap<>();
        getContents("", json, properties);
        logger.info("loaded {} properties from XML source: {}", properties.size(), getSourceName());
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
