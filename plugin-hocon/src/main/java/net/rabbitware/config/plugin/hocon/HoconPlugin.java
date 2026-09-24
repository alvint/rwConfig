package net.rabbitware.config.plugin.hocon;
import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigIncluderClasspath;
import com.typesafe.config.ConfigIncluderFile;
import com.typesafe.config.ConfigIncluderURL;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;
import net.rabbitware.config.plugin.api.ListValues;

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
 * <p>
 * It also accepts one optional property:
 * <ul>
 * <li>
 * {@code rwc.<sourceName>.trusted} - whether to allow the HOCON directives
 * that reach outside the document: {@code include}, and substitutions that
 * fall back to system properties and environment variables. Defaults to
 * {@code false}, because a source can come from somewhere less trusted than
 * your code, and those directives are enough to read local files into the
 * configuration or make the application issue requests to other servers. Set
 * it to {@code true} only for a source as trusted as your jar.
 * </li>
 * </ul>
 */
public class HoconPlugin extends LocationBasedConfigSourcePlugin {
    private static final Logger logger = LoggerFactory.getLogger(HoconPlugin.class);
    private boolean trusted = false; // default is false

    public HoconPlugin() {
        logger.info("HOCON plugin instantiated");
    }


    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        // combine the optional properties from the superclass with the
        // HOCON-specific ones
        Set<String> names = new HashSet<>(super.getOptionalPluginPropertyNames());
        names.add("trusted"); // default is false
        return names;
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        // set and validate `location`, `username`, and `password`
        super.setPluginProperties(properties);
        // set and validate `trusted` property
        trusted = LocationBasedConfigSourcePlugin.parseBoolean(
            properties.getOrDefault("trusted", "false")
        );
        if (trusted) {
            logger.info(
                "HOCON source `{}` is trusted: `include`, and substitutions from system properties"
                + " and the environment, in `{}` will be carried out",
                getSourceName(), getLocation()
            );
        }
    }


    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading HOCON source from location: {}", getLocation());
        String sourceContent = loadLocation();
        // parse the HOCON content and flatten it into a map of properties.
        ConfigParseOptions parseOptions = ConfigParseOptions.defaults();
        ConfigResolveOptions resolveOptions = ConfigResolveOptions.defaults();
        if (!trusted) {
            parseOptions = parseOptions.setIncluder(new RefusingIncluder(getSourceName()));
            resolveOptions = resolveOptions.setUseSystemEnvironment(false);
        }
        Config config = ConfigFactory.parseString(sourceContent, parseOptions);
        // A trusted document can also substitute system properties. They are
        // only a fallback for looking values up - `resolveWith` returns the
        // document's own keys, and the document wins where both have a path.
        // `ConfigFactory.load` would instead merge every system property into
        // the result and let them override the document.
        Config lookup = trusted
            ? config.withFallback(ConfigFactory.parseProperties(systemProperties()))
            : config;
        config = config.resolveWith(lookup, resolveOptions);
        Map<String, String> properties = new HashMap<>();
        getContents("", config.root(), properties);
        logger.info("loaded {} properties from HOCON source: {}", properties.size(), getSourceName());
        return properties;
    }

    /**
     * The system properties as Typesafe Config sees them, read fresh.
     * <p>
     * This matches what {@code ConfigFactory.systemProperties()} returns, which
     * cannot be used directly because it is cached for the life of the JVM, and
     * a property set after the first load would be missed. It copies under the
     * properties' own lock, since another thread setting one mid-copy would
     * throw, and it drops the {@code java.version.*} keys: parsed as a tree,
     * {@code java.version.date} would turn {@code java.version} from a string
     * into an object, and Typesafe Config makes the same exception.
     */
    private static Properties systemProperties() {
        Properties system = System.getProperties();
        Properties copy = new Properties();
        synchronized (system) {
            for (Map.Entry<Object, Object> entry : system.entrySet()) {
                if (!entry.getKey().toString().startsWith("java.version.")) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return copy;
    }

    /**
     * A scalar as text - and a number exactly as it was written.
     * <p>
     * Typesafe Config parses a number into a {@code double}, {@code int} or
     * {@code long}, so its unwrapped value has lost what was written:
     * {@code 1.00000000000000000001} becomes {@code 1}, and {@code 2.00}
     * becomes the integer {@code 2}. It keeps the original text alongside,
     * and hands that back when a number is read as a string, which is what
     * this asks for. The text survives substitutions, so {@code copy = ${m}}
     * copies {@code m} exactly as written too.
     */
    private static String text(ConfigValue value) {
        return value.valueType() == ConfigValueType.NUMBER
            ? value.atKey("value").getString("value")
            : String.valueOf(value.unwrapped());
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
                if (ListValues.allAreValues(list, HoconPlugin::isValue)) { // one list value
                    add(map, prefix, ListValues.join(list, HoconPlugin::text));
                } else { // indexed
                    for (int i = 0; i < list.size(); i++) {
                        getContents(prefix.isEmpty() ? String.valueOf(i) : prefix + "\\" + i, list.get(i), map);
                    }
                }
            }
            default -> {
                switch (value.valueType()) {
                    case STRING, NUMBER, BOOLEAN, NULL -> add(map, prefix, text(value));
                    default -> logger.warn("unsupported value type at prefix `{}`: {}", prefix, value.valueType());
                }
            }
        }
    }


    // a value that can be one item of a list, rather than an object or a list
    private static boolean isValue(ConfigValue value) {
        return switch (value.valueType()) {
            case NULL, STRING, NUMBER, BOOLEAN -> true;
            case OBJECT, LIST -> false;
        };
    }

    private void add(Map<String, String> map, String key, Object value) {
        if (map.containsKey(key)) {
            logger.error("REPORT THIS BUG! DATA LOSS! overwriting existing property: {} with value: {}", key, value);
        }
        map.put(key, String.valueOf(value));
    }


    /**
     * An includer that carries out no include at all, and says so.
     * <p>
     * Every form is refused the same way - {@code include "other.conf"},
     * {@code include file(...)}, {@code include url(...)}, and
     * {@code include classpath(...)} - and refused loudly rather than skipped,
     * because an include that quietly did nothing would leave properties unset
     * and the reason several errors away.
     */
    private static class RefusingIncluder
        implements ConfigIncluder, ConfigIncluderFile, ConfigIncluderURL, ConfigIncluderClasspath
    {
        private final String sourceName;

        RefusingIncluder(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            return this; // there is nothing to fall back to - we refuse everything
        }

        @Override
        public ConfigObject include(ConfigIncludeContext context, String what) {
            throw refuse(what);
        }

        @Override
        public ConfigObject includeFile(ConfigIncludeContext context, File what) {
            throw refuse("file(\"" + what + "\")");
        }

        @Override
        public ConfigObject includeURL(ConfigIncludeContext context, URL what) {
            throw refuse("url(\"" + what + "\")");
        }

        @Override
        public ConfigObject includeResources(ConfigIncludeContext context, String what) {
            throw refuse("classpath(\"" + what + "\")");
        }

        private ConfigException refuse(String what) {
            return new ConfigException.Generic(
                "HOCON source `" + sourceName + "` contains `include " + what + "`, which is not carried out"
                + " because the source is not trusted. Declare the other document as its own config source,"
                + " or set `rwc." + sourceName + ".trusted = true` if this source is as trusted as your code."
            );
        }
    }
}
