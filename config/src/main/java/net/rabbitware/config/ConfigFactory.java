package net.rabbitware.config;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.Config.ConfigException;
import net.rabbitware.config.Config.PropertyType;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

public class ConfigFactory {
    public static final String CONFIG_FILE_PATH_PROPERTY = "rw.config.path";
    public static final String DEFAULT_CONFIG_FILE_PATH = "rwconfig";
    public static final String DEFAULT_CONFIG_PREFIX = "rwc.";
    public static final String CONFIG_PREFIX_PROPERTY = DEFAULT_CONFIG_PREFIX + "prefix";

    /**
     * This special value tells the library to look for the real value of this
     * property in a previously loaded config source.
     */
    public static final String DEFERRED_VALUE = "<<";

    /**
     * A command line argument of the form `name=value`, where `name` is a
     * legal property name. Names begin with a letter, so an application's own
     * flags are never mistaken for property assignments.
     */
    /** The suffix that marks a source type as a plugin rather than a built-in. */
    private static final String PLUGIN_TYPE_SUFFIX = ".plugin";

    /** Plugin module names are this prefix followed by the plugin's short name. */
    private static final String PLUGIN_MODULE_PREFIX = "net.rabbitware.config.plugin.";

    private static final Pattern COMMAND_LINE_ASSIGNMENT =
        Pattern.compile("^\\s*([A-Za-z][\\w.\\\\-]*)\\s*=\\s*(.*)$");

    private static final Logger logger = LoggerFactory.getLogger(ConfigFactory.class);

    public static Config create() throws ConfigException {
        return create(null);
    }

    public static Config create(String[] commandLineArgs) throws ConfigException {
        logger.debug(
            "creating Config instance with command line arguments {}",
            commandLineArgs == null ? "not set" : "set"
        );
        List<String> configFile = loadConfigFile(commandLineArgs);
        // Figure out the config prefix - the string every library setting
        // starts with. It applies to the whole file, so it has to be the first
        // line that is not a comment or blank: anywhere else and the lines
        // above it would mean one thing on the way down and another on the way
        // back up, which is as confusing for a reader as it is for an editor
        // trying to highlight them.
        List<String> declarations = configFile.stream()
            .map(s -> s.replaceAll("^\\s*[!#].*$", "")) // remove comments
            .filter(s -> !s.trim().isEmpty()) // remove empty lines
            .toList();
        String prefix = DEFAULT_CONFIG_PREFIX;
        for (int i = 0; i < declarations.size(); i++) {
            String[] parts = declarations.get(i).split("=", 2);
            if (parts.length != 2 || !parts[0].trim().equals(CONFIG_PREFIX_PROPERTY)) {
                continue;
            }
            if (i != 0) {
                throw new ConfigException(
                    "`" + CONFIG_PREFIX_PROPERTY + "` must be the first line of the `rwconfig` file that is"
                    + " not a comment or blank, because it applies to every line: " + declarations.get(i).trim()
                );
            }
            prefix = parts[1].trim();
        }
        // the lambdas below close over this, so it has to be effectively final
        final String configPrefix = prefix;
        // parse the library settings and property info from the `rwconfig` file
        Map<String, String> configProperties = new HashMap<>();
        Map<String, PropertyInfo> propertyInfoMap = new HashMap<>();

        // Create a regex pattern to help parse config lines.
        //
        // The regex is a bit complicated, but it should be able to handle all
        // (syntactically) valid config lines, including those with escaped
        // characters, whitespace, and comments. It should also reject all lines
        // that are (syntactically) invalid, such as those with missing fields
        // and improperly escaped characters.
        //
        // The regex does not check for semantic validity, such as whether a
        // property has an allowed value for its type. Those checks are done
        // later, after the config line has been parsed.
        //
        // the capture groups are:
        // 1. the type of the property (optional)
        //    - if the type is not specified, it defaults to `string`
        //    - leading and trailing whitespace is ignored
        //    - a type only counts as a type if whitespace or a `[` follows it,
        //      which is what the lookahead after the type is for. without it a
        //      property name that starts with the name of a type would have
        //      that prefix taken as its type - `longitude = 1` would declare a
        //      property named `itude` of type `long`
        // 2. the allowed values of the property (optional)
        //    - if the allowed values are not specified, any value is allowed
        //    - leading whitespace is ignored, but trailing whitespace is kept
        //      to allow for values that end with whitespace, such as "foo " and
        //      "bar "
        //      - to create a value that starts with whitespace, escape the
        //        first space with a backslash
        //    - allowed values accept the same escape sequences as values (`\\`,
        //      `\e`, `\n`, `\r`, `\t`, and a unicode escape - a backslash, then
        //      a `u`, then four hex digits), plus `\]`, `\,` and `\:`, which
        //      have to be escaped here because each one is otherwise meaningful
        //      inside the list. `[` is not meaningful once the list is open, so
        //      it is used as-is (and cannot be escaped - `\[` is not an escape
        //      sequence anywhere in this file)
        //      - note that a unicode escape cannot be written out in this
        //        comment, since javac expands those before it parses comments
        // 3. the name of the property (required)
        //    - leading and trailing whitespace is ignored
        // 4. the value of the property (optional)
        //    - leading whitespace is ignored, but trailing whitespace is kept
        //      to allow for values that end with whitespace, such as "foo " and
        //      "bar "
        //      - to create a value that starts with whitespace, escape the
        //        first space with a backslash
        //      - an escaped space anywhere else does nothing, and is logged as
        //        a warning while the value is unescaped rather than checked
        //        here, since a value is not broken into its parts until then
        //
        // If you find a valid config line that is not matched by this regex,
        // please report it as a bug.
        String types = Stream.of(PropertyType.values())
            .map(t -> t.name)
            .reduce((a, b) -> a + "|" + b)
            .orElse("");
        var pattern = Pattern.compile(
"^[^\\S\\n\\r]*((?i:{types})(?=[^\\S\\n\\r]|\\[))?[^\\S\\n\\r]*(?:\\[\\s*((?:(?:(?:\\\\[\\\\\\],: enrtu]|[^\\\\\\],:\\s])(?:\\\\[\\\\\\],:enrtu]|[^\\\\\\],:])*)(?::\\s*(?:(?:\\\\[\\\\\\],: enrtu]|[^\\\\\\],:\\s])(?:\\\\[\\\\\\],:enrtu]|[^\\\\\\],:])*))?)(?:,\\s*(?:(?:(?:\\\\[\\\\\\],: enrtu]|[^\\\\\\],:\\s])(?:\\\\[\\\\\\],:enrtu]|[^\\\\\\],:])*)(?::\\s*(?:(?:\\\\[\\\\\\],: enrtu]|[^\\\\\\],:\\s])(?:\\\\[\\\\\\],:enrtu]|[^\\\\\\],:])*))?))*)\\])?\\s*([A-Za-z][\\w\\.\\\\-]*)\\s*(?:=[^\\S\\n\\r]*([^\\n\\r]*))?$"
            .replace("{types}", types)
        );
        configFile.stream()
            .map(s -> s.replaceAll("^\\s*[!#].*$", "")) // remove comments
            .filter(s -> !s.trim().isEmpty()) // remove empty lines
            .forEach(s -> {
                var matcher = pattern.matcher(s);
                if (!matcher.matches()) {
                    throw new ConfigException("invalid config line: " + s);
                }
                var type = matcher.group(1);
                var allowedValues = matcher.group(2);
                var name = matcher.group(3);
                var defaultValue = matcher.group(4);
                if (configProperties.containsKey(name) || propertyInfoMap.containsKey(name)) {
                    throw new ConfigException("duplicate config line for property: " + name);
                }
                if (name.startsWith(configPrefix) || name.equals(CONFIG_PREFIX_PROPERTY)) { // library setting
                    if (type != null && !type.isEmpty()) {
                        throw new ConfigException("invalid config line (library settings cannot have a type): " + s);
                    }
                    if (allowedValues != null && !allowedValues.isEmpty()) {
                        throw new ConfigException(
                            "invalid config line (library settings cannot have allowed values): " + s
                        );
                    }
                    if (defaultValue == null || defaultValue.isEmpty()) {
                        throw new ConfigException("invalid config line (missing value): " + s);
                    }
                    configProperties.put(name, defaultValue);
                } else { // property info line
                    type = type != null && !type.isEmpty() ? type : "string"; // default type is `string`
                    logger.debug(
                        "loading property info for `{}`: type=`{}`, allowedValues=`{}`, defaultValue=`{}`",
                        name, type, allowedValues, defaultValue
                    );
                    propertyInfoMap.put(name, getPropertyInfo(name, type, allowedValues, defaultValue));
                }
            });
        logger.debug(
            "`rwconfig` file loaded successfully with {} library settings and {} property declarations",
            configProperties.size(), propertyInfoMap.size())
        ;

        // get all of the config sources
        Map<String, Map<String, String>> configSources = new LinkedHashMap<>();
        // `sources` is optional. Without it there are no config sources, and
        // every property takes the default value declared in the `rwconfig`
        // file - which is a complete, if static, configuration, and is the
        // smallest useful file anyone can write. A property with no default
        // still fails at startup, since nothing can supply it.
        String sourceNames = configProperties.getOrDefault(configPrefix + "sources", "");
        Stream.of(sourceNames.trim().split("\\s*,\\s*", -1))
            .filter(sourceName -> !sourceNames.trim().isEmpty())
            .forEach(sourceName -> {
                if (sourceName.isEmpty()) {
                    throw new ConfigException(
                        "invalid `" + configPrefix + "sources` property (empty source name): " + sourceNames
                    );
                }
                if (configSources.containsKey(sourceName)) {
                    throw new ConfigException("duplicate config source name: " + sourceName);
                }
                logger.debug("loading config source: {}", sourceName);
                String type = getPluginProperty(configProperties, configSources, configPrefix, sourceName, "type", true);
                if (type.indexOf('.') != -1) { // sourceType is a plugin
                    SimpleConfigSourcePlugin plugin = loadPluginByModule(type);
                    String pluginVersion = plugin.getPluginVersion();
                    logger.info(
                        "using plugin `{}` v{} for config source `{}`",
                        plugin.getClass().getName(), pluginVersion, sourceName
                    );
                    plugin.setSourceName(sourceName);
                    // get the required and optional properties for the plugin
                    Set<String> requiredProperties = Set.copyOf(plugin.getRequiredPluginPropertyNames());
                    Set<String> optionalProperties = Set.copyOf(plugin.getOptionalPluginPropertyNames());
                    // get the properties for the plugin from the `rwconfig` file
                    Map<String, String> properties = new HashMap<>();
                    requiredProperties.stream()
                        .forEach(propertyName -> {
                            String propertyValue = getPluginProperty(
                                configProperties, configSources, configPrefix, sourceName, propertyName, true
                            );
                            properties.put(propertyName, propertyValue);
                        });
                    optionalProperties.stream()
                        .forEach(propertyName -> {
                            String propertyValue = getPluginProperty(
                                configProperties, configSources, configPrefix, sourceName, propertyName, false
                            );
                            properties.put(propertyName, propertyValue);
                        });
                    // set the properties for the plugin
                    try {
                        plugin.setPluginProperties(properties);
                    } catch (Exception e) {
                        throw new ConfigException(
                            "error setting properties for config source `" + sourceName + "` of type `" + type
                            + "`", e
                        );
                    }
                    // get the config source properties from the plugin
                    Map<String, String> configSourceProperties;
                    try {
                        configSourceProperties = plugin.getConfigSourceProperties();
                    } catch (Exception e) {
                        throw new ConfigException(
                            "error getting properties for config source `" + sourceName + "` of type `" + type
                            + "`", e
                        );
                    }
                    if (configSourceProperties == null) {
                        throw new ConfigException(
                            "config source `" + sourceName + "` of type `" + type
                            + "` returned null from getConfigSourceProperties()"
                        );
                    }
                    configSources.put(sourceName, Map.copyOf(configSourceProperties));
                } else { // sourceType is a built-in source
                    switch (SourceType.fromString(type)) {
                        case COMMAND_LINE_ARGUMENTS -> {
                            if (commandLineArgs == null) {
                                throw new ConfigException(
                                    "config source `" + sourceName + "` is of type `commandLineArguments`, "
                                    + "but no command line arguments were provided to the config system"
                                );
                            }
                            configSources.put(sourceName, new CommandLineProperties(commandLineArgs, configPrefix));
                            logger.debug("config source `{}` loaded from command line arguments", sourceName);
                        }
                        case SYSTEM_PROPERTIES -> {
                            configSources.put(sourceName, new SystemProperties(propertyInfoMap));
                            logger.debug("config source `{}` loaded from system properties", sourceName);
                        }
                        case ENVIRONMENT_VARIABLES -> {
                            configSources.put(sourceName, new EnvironmentProperties(propertyInfoMap));
                            logger.debug("config source `{}` loaded from environment variables", sourceName);
                        }
                        case PROPERTIES -> {
                            String location = getPluginProperty(configProperties, configSources, configPrefix, sourceName, "location", true);
                            logger.info("setting plugin properties: location={}", location);
                            if (!SimpleConfigSourcePlugin.isSupportedLocation(location)) {
                                throw new ConfigException("unsupported location: " + location);
                            }
                            try {
                                String sourceContent = SimpleConfigSourcePlugin.loadResource(location);
                                Properties properties = new Properties();
                                properties.load(new StringReader(sourceContent));
                                configSources.put(sourceName, toMap(properties));
                                logger.debug(
                                    "config source `{}` loaded {} properties from location: {}",
                                    sourceName, properties.size(), location
                                );
                            } catch (Exception e) {
                                throw new ConfigException(
                                    "error loading properties from location for config source `" + sourceName + "`: "
                                    + location, e
                                );
                            }
                        }
                        case DIRECTORY -> {
                            String dirPath = getPluginProperty(configProperties, configSources, configPrefix, sourceName, "path", true);
                            try (Stream<Path> files = Files.list(Path.of(dirPath))) {
                                Map<String, String> properties = new HashMap<>();
                                files
                                    .filter(Files::isRegularFile)
                                    .forEach(path -> {
                                        try {
                                            String fileName = path.getFileName().toString();
                                            String content = Files.readString(path, StandardCharsets.UTF_8);
                                            properties.put(fileName, content);
                                        } catch (IOException e) {
                                            throw new ConfigException(
                                                "error reading config source file from directory for `" + sourceName + "`: " + path, e
                                            );
                                        }
                                    });
                                configSources.put(sourceName, properties);
                                logger.debug("config source `{}` loaded from directory: {}", sourceName, dirPath);
                            } catch (IOException e) {
                                throw new ConfigException(
                                    "error reading directory contents for `" + sourceName + "`: " + dirPath, e
                                );
                            }
                        }
                    }
                }
            });

        // create the config object and set its values
        var configImpl = new ConfigImpl();
        configSources.entrySet().stream()
            .forEach(entry -> {
                String sourceName = entry.getKey();
                boolean ignoreUnknownProperties = configProperties
                    .getOrDefault(configPrefix + sourceName + ".ignoreUnknownProperties", "false")
                    .matches("(?i)true|yes|on|1");
                Map<String, String> properties = entry.getValue();
                properties.keySet().stream()
                    .filter(s -> !configImpl.has(s))
                    .forEach(name -> {
                        String valueString = properties.get(name);
                        if (!propertyInfoMap.containsKey(name) && !configProperties.containsKey(name)) {
                            if (!ignoreUnknownProperties) {
                                throw new ConfigException(
                                    "property `"
                                        + name
                                        + "` is not defined in the `rwconfig` file, and config source `"
                                        + sourceName
                                        + "` does not allow unknown properties"
                                );
                            } else {
                                logger.info(
                                    "property `{}` is not defined in the `rwconfig` file, but is present in config source `{}`"
                                    + " - it will be ignored",
                                    name, sourceName
                                );
                            }
                        } else if(!configProperties.containsKey(name)) {
                            PropertyInfo propertyInfo = propertyInfoMap.get(name);
                            Value value = parseValue(
                                sourceName, name, valueString, propertyInfo.propertyType, propertyInfo.allowedValues
                            );
                            // do not log the value of the property - it may contain
                            // sensitive information
                            logger.debug("setting property `{}` from config source `{}`", name, sourceName);
                            configImpl.add(name, value);
                        }
                    });
            });

        // set the default values for any properties that were not set by the
        // config sources, but have a default value defined in the `rwconfig` file
        //
        // if a property has no default value defined in the `rwconfig` file, and is 
        // not set by any config source, throw a ConfigException
        propertyInfoMap.entrySet().stream()
            .filter(entry -> !configImpl.has(entry.getKey()))
            .forEach(entry -> {
                String name = entry.getKey();
                PropertyInfo propertyInfo = entry.getValue();
                if (propertyInfo.defaultValue != null) {
                    logger.debug("setting property `{}` to its default value of `{}`", name, propertyInfo.defaultValue);
                    configImpl.add(name, propertyInfo.defaultValue);
                } else {
                    throw new ConfigException(
                        "property `" + name
                        + "` is not set by any config source, and has no default value defined in the `rwconfig` file"
                    );
                }
            });
        // every property has been added by this point, so the config can take
        // its final set of property names
        configImpl.freeze();
        logger.debug("Config instance created successfully");
        return configImpl;
    }


    //
    // private stuff
    //

    private static record PropertyInfo (
        PropertyType propertyType,
        List<Range> allowedValues,
        Value defaultValue
    ) {}

    private static record Range(Value min, Value max) {}

    private static List<String> loadConfigFile(String[] commandLineArgs) throws ConfigException {
        String configFilePath = getConfigFilePath(commandLineArgs);
        return loadConfigFile(configFilePath);
    }

    private static List<String> loadConfigFile(String location) throws ConfigException {
        // check if the config file path is a valid location
        if (SimpleConfigSourcePlugin.isSupportedLocation(location)) { // valid location
            // load the `rwconfig` file from the location
            try {
                List<String> lines = SimpleConfigSourcePlugin.loadResource(location).lines().toList();
                // join lines that end with a backslash with the next line, and
                // remove the backslash
                List<String> joinedLines = new LinkedList<>();
                StringBuilder currentLine = new StringBuilder();
                for (String line : lines) {
                    boolean continues = line.endsWith("\\");
                    currentLine.append(continues ? line.substring(0, line.length() - 1) : line);
                    if (!continues) {
                        joinedLines.add(currentLine.toString());
                        currentLine.setLength(0);
                    }
                }
                if (currentLine.length() > 0) {
                    joinedLines.add(currentLine.toString());
                }
                return joinedLines;
            } catch (Exception e) {
                throw new ConfigException("error loading config file from location: " + location, e);
            }
        } else { // not a valid location
            // try to load the `rwconfig` file from the classpath first, then the
            // filesystem
            try { return loadConfigFile("classpath:" + location); } catch (Exception e) {}
            try { return loadConfigFile("file:" + location); } catch (Exception e) {}
            throw new ConfigException("config file path is not a valid location: " + location);
        }
    }

    private static String getConfigFilePath(String[] commandLineArgs) {
        // try to get the config file path from the command line arguments
        String configFilePath = getCommandLineArgument(CONFIG_FILE_PATH_PROPERTY, commandLineArgs);
        if (configFilePath != null) {
            logger.debug("config file path specified in command line arguments: {}", configFilePath);
            return configFilePath;
        }

        // try to get the config file path from the system properties
        configFilePath = getSystemProperty(CONFIG_FILE_PATH_PROPERTY);
        if (configFilePath != null) {
            logger.debug("config file path specified in system properties: {}", configFilePath);
            return configFilePath;
        }

        // try to get the config file path from the environment variables
        configFilePath = getEnvironmentVariable(CONFIG_FILE_PATH_PROPERTY);
        if (configFilePath != null) {
            logger.debug("config file path specified in environment variables: {}", configFilePath);
            return configFilePath;
        }

        // use the default config file path if none was specified
        configFilePath = DEFAULT_CONFIG_FILE_PATH;
        logger.debug("using default config file path: {}", configFilePath);
        return configFilePath;

    }

    private static PropertyInfo getPropertyInfo(String name, String type, String allowedValues, String defaultValue)
            throws ConfigException {
        PropertyType propertyType = PropertyType.fromString(type);
        List<Range> allowedValuesList = parseAllowedValues(name, propertyType, allowedValues);
        Value value;
        if (defaultValue == null) {
            value = null;
        } else {
            value = parseValue(null,name, defaultValue, propertyType, allowedValuesList);
        }
        return new PropertyInfo(propertyType, allowedValuesList, value);
    }

    private static List<Range> parseAllowedValues(String name, PropertyType propertyType, String allowedValues)
            throws ConfigException {
        List<Range> ranges = new LinkedList<>();
        if (allowedValues != null) {
            PropertyType rangeType = 
                switch (propertyType) {
                    case BOOLEAN, BOOLEAN_LIST -> PropertyType.BOOLEAN;
                    case INT, INT_LIST -> PropertyType.INT;
                    case LONG, LONG_LIST -> PropertyType.LONG;
                    case DOUBLE, DOUBLE_LIST -> PropertyType.DOUBLE;
                    case STRING, STRING_LIST -> PropertyType.STRING;
                    case DURATION, DURATION_LIST -> PropertyType.DURATION;
                    case SIZE, SIZE_LIST -> PropertyType.SIZE;
                };
            Stream.of(allowedValues.split("(?<!\\\\),\\s*", -1)) // remove leading whitespace but not trailing
                .forEach(rangeString -> {
                    String[] minMax = rangeString.split("(?<!\\\\):\\s*", -1); // remove leading whitespace only
                    if (minMax.length > 2) {
                        throw new ConfigException(
                            "invalid allowed value range for property `" + name + "`: " + rangeString
                        );
                    }
                    Value min = parseValue(null, name, minMax[0], rangeType, List.of());
                    Value max = minMax.length == 2 ? parseValue(null, name, minMax[1], rangeType, List.of()) : min;
                    ranges.add(new Range(min, max));
                });
        }
        return List.copyOf(ranges);
    }

    // this method parses a value string into a Value object of the appropriate
    // type, and checks if the value is allowed
    //
    // this method can recursively call itself (once) to produce list values
    private static Value parseValue(
        String sourceName,
        String propertyName,
        String valueString,
        PropertyType propertyType,
        List<Range> allowedValues
    ) throws ConfigException {
        try {
            return switch (propertyType) {
                case BOOLEAN -> {
                    // unescape any escaped characters in the value string (and
                    // trim whitespace)
                    var unescapedValueString = handleEscapeSequences(sourceName, valueString).trim();
                    // parse
                    boolean value;
                    if (
                        "true".equalsIgnoreCase(unescapedValueString) ||
                        "yes".equalsIgnoreCase(unescapedValueString) ||
                        "on".equalsIgnoreCase(unescapedValueString) ||
                        "1".equals(unescapedValueString)
                    ) {
                        value = true;
                    } else if (
                        "false".equalsIgnoreCase(unescapedValueString) ||
                        "no".equalsIgnoreCase(unescapedValueString) ||
                        "off".equalsIgnoreCase(unescapedValueString) ||
                        "0".equals(unescapedValueString)
                    ) {
                        value = false;
                    } else {
                        throw new NumberFormatException(
                            "invalid boolean value for property `" + propertyName + "`: " + unescapedValueString
                        );
                    }
                    // check if value is allowed
                    if (!allowedValues.isEmpty() && allowedValues.stream().noneMatch(range ->
                        ((Value.Boolean)range.min).b == value || ((Value.Boolean)range.max).b == value
                    )) {
                        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): "
                            + unescapedValueString
                        );
                    }
                    // value is allowed; return it
                    yield new Value.Boolean(value);
                }
                case INT -> {
                    // unescape any escaped characters in the value string (and
                    // trim whitespace)
                    var unescapedValueString = handleEscapeSequences(sourceName, valueString).trim();
                    // parse
                    int value = Integer.parseInt(unescapedValueString);
                    // check if value is allowed
                    if (!allowedValues.isEmpty() && allowedValues.stream().noneMatch(range ->
                        ((Value.Integer)range.min).i <= value && value <= ((Value.Integer)range.max).i
                    )) {
                        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): "
                            + unescapedValueString
                        );
                    }
                    // value is allowed; return it
                    yield new Value.Integer(value);
                }
                case DURATION, SIZE -> {
                    // unescape any escaped characters in the value string (and
                    // trim whitespace)
                    var unescapedValueString = handleEscapeSequences(sourceName, valueString).trim();
                    // parse the number and its unit into the canonical unit -
                    // milliseconds for a duration, bytes for a size
                    long value = propertyType == PropertyType.DURATION
                        ? parseDuration(propertyName, sourceName, unescapedValueString)
                        : parseSize(propertyName, sourceName, unescapedValueString);
                    // check if value is allowed. The range was parsed in the
                    // same units, so both sides are already canonical
                    if (!allowedValues.isEmpty() && allowedValues.stream().noneMatch(range ->
                        ((Value.Long)range.min).l <= value && value <= ((Value.Long)range.max).l
                    )) {
                        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): "
                            + unescapedValueString
                        );
                    }
                    // value is allowed; return it
                    yield new Value.Long(value);
                }
                case LONG -> {
                    // unescape any escaped characters in the value string (and
                    // trim whitespace)
                    var unescapedValueString = handleEscapeSequences(sourceName, valueString).trim();
                    // parse
                    long value = Long.parseLong(unescapedValueString);
                    // check if value is allowed
                    if (!allowedValues.isEmpty() && allowedValues.stream().noneMatch(range ->
                        ((Value.Long)range.min).l <= value && value <= ((Value.Long)range.max).l
                    )) {
                        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): "
                            + unescapedValueString
                        );
                    }
                    // value is allowed; return it
                    yield new Value.Long(value);
                }
                case DOUBLE -> {
                    // unescape any escaped characters in the value string (and
                    // trim whitespace)
                    var unescapedValueString = handleEscapeSequences(sourceName, valueString).trim();
                    // parse
                    double value = Double.parseDouble(unescapedValueString);
                    // check if value is allowed
                    if (!allowedValues.isEmpty() && allowedValues.stream().noneMatch(range ->
                        ((Value.Double)range.min).d <= value && value <= ((Value.Double)range.max).d
                    )) {
                        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): "
                            + unescapedValueString
                        );
                    }
                    // value is allowed; return it
                    yield new Value.Double(value);
                }
                case STRING -> {
                    // unescape any escaped characters in the value string
                    var unescapedValueString = handleEscapeSequences(sourceName, valueString);
                    // check if value is allowed
                    if (!allowedValues.isEmpty() && allowedValues.stream().noneMatch(range ->
                        ((Value.String)range.min).s.compareTo(unescapedValueString) <= 0 &&
                        ((Value.String)range.max).s.compareTo(unescapedValueString) >= 0
                    )) {
                        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): \""
                            + unescapedValueString + "\""
                        );
                    }
                    // value is allowed; return it
                    yield new Value.String(unescapedValueString);
                }
                // for list types, we don't check the allowed values here -
                // we check them when we parse the individual values
                case BOOLEAN_LIST -> {
                    List<Value.Boolean> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Boolean)
                                parseValue(sourceName, propertyName, s, PropertyType.BOOLEAN, allowedValues))
                            .toList();
                    yield new Value.BooleanList(list);
                }
                case INT_LIST -> {
                    List<Value.Integer> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Integer)
                                parseValue(sourceName, propertyName, s, PropertyType.INT, allowedValues))
                            .toList();
                    yield new Value.IntegerList(list);
                }
                case LONG_LIST -> {
                    List<Value.Long> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Long)
                                parseValue(sourceName, propertyName, s, PropertyType.LONG, allowedValues))
                            .toList();
                    yield new Value.LongList(list);
                }
                case DOUBLE_LIST -> {
                    List<Value.Double> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Double)
                                parseValue(sourceName, propertyName, s, PropertyType.DOUBLE, allowedValues))
                            .toList();
                    yield new Value.DoubleList(list);
                }
                case DURATION_LIST, SIZE_LIST -> {
                    PropertyType itemType = propertyType == PropertyType.DURATION_LIST
                        ? PropertyType.DURATION
                        : PropertyType.SIZE;
                    List<Value.Long> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s -> (Value.Long)
                                parseValue(sourceName, propertyName, s, itemType, allowedValues))
                            .toList();
                    yield new Value.LongList(list);
                }
                case STRING_LIST -> {
                    // preserve trailing whitespace (but not leading), and split
                    // on unescaped commas
                    List<Value.String> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),\\s*", -1))
                            .map(s ->(Value.String)
                                parseValue(sourceName, propertyName, s, PropertyType.STRING, allowedValues))
                            .toList();
                    yield new Value.StringList(list);
                }
            };
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
            throw new ConfigException(
                "error parsing the value of `" + propertyName + "` (in " + source + ") as type `"
                + propertyType.name + "`: "+ valueString, e
            );
        }
    }

    private static Map<String, String> toMap(Properties properties) {
        return properties.stringPropertyNames().stream()
            .collect(Collectors.toMap(name -> name, name -> properties.getProperty(name)));
    }
    /**
     * Retrieve the value of the command line argument corresponding to the
     * given property name. Commands line arguments are expected to be in the
     * form of {@code <propertyName>=value} (all one argument).
     *
     * @param propertyName
     * the name of the command line argument to retrieve
     * @param commandLineArgs
     * the array of command line arguments
     * @return
     * the value of the command line argument, or {@code null} if not found
     */
    private static String getCommandLineArgument(String propertyName, String[] commandLineArgs) {
        if (commandLineArgs == null || commandLineArgs.length == 0) {
            return null;
        }
        // create a regex pattern to match `<propertyName>=value`
        var pattern = Pattern.compile("^\\s*" + Pattern.quote(propertyName) + "\\s*=\\s*(.*)\\s*$");
        // return the value of the first command line argument that matches the
        // pattern
        return Stream.of(commandLineArgs)
            .filter(s -> s != null)
            .map(pattern::matcher)
            .filter(Matcher::matches)
            .map(matcher -> matcher.group(1))
            .findFirst()
            .orElse(null);
    }

    /**
     * Retrieve the value of the system property corresponding to the given
     * property name.
     *
     * @param propertyName
     * the name of the system property to retrieve
     * @return
     * the value of the system property, or {@code null} if not found
     */
    private static String getSystemProperty(String propertyName) {
        return System.getProperty(propertyName);
    }

    /**
     * Retrieve the value of the environment variable corresponding to the given
     * property name. The environment is first searched using the property name
     * as-is. If the environment variable is not found under that name, the
     * property name is converted to an environment-variable-friendly name and
     * the environment is searched again. This conversion is done by replacing
     *  camelCase and dots with underscores, and then converting to uppercase.
     *
     * @param propertyName
     * the name of the environment variable to retrieve
     * @return
     * the value of the environment variable, or {@code null} if not found
     */
    private static String getEnvironmentVariable(String propertyName) {
        String value = System.getenv(propertyName); // try to get the property value from the environment variables
        if (value == null) { // not found - retry after converting the property name to an environment variable name
            String envName = propertyName
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[\\.-]", "_")
                .toUpperCase();
            value = System.getenv(envName);
        }
        return value;
    }

    /**
     * The units a `duration` accepts, and how many milliseconds each is worth.
     * Milliseconds are the canonical unit, so a value with no unit is read as
     * milliseconds. Nothing finer is offered: a `duration` is a whole number of
     * milliseconds, and accepting `us` or `ns` would mean either silently
     * truncating or accepting some values of a unit and rejecting others.
     *
     * <p>Note that no duration unit ends in `B` and every size unit does, so
     * the two vocabularies cannot overlap even though `m` and `MB` both begin
     * with the same letter.
     */
    private static final Map<String, Long> DURATION_UNITS = Map.of(
        "ms", 1L,
        "s", 1000L,
        "m", 60L * 1000,
        "h", 60L * 60 * 1000,
        "d", 24L * 60 * 60 * 1000
    );

    /**
     * The units a `size` accepts, and how many bytes each is worth. Bytes are
     * the canonical unit, so a value with no unit is read as bytes.
     *
     * <p>`KB` and `KiB` mean different things, deliberately: the SI prefixes
     * are powers of 1000 and the IEC ones powers of 1024, as the standards
     * define them. Bare `K`, `M` and `G` are not accepted, because in the wild
     * they mean either one depending on who wrote them - being made to spell
     * out which is the point.
     */
    private static final Map<String, Long> SIZE_UNITS = Map.of(
        "B", 1L,
        "KB", 1000L,
        "MB", 1000L * 1000,
        "GB", 1000L * 1000 * 1000,
        "TB", 1000L * 1000 * 1000 * 1000,
        "KiB", 1024L,
        "MiB", 1024L * 1024,
        "GiB", 1024L * 1024 * 1024,
        "TiB", 1024L * 1024 * 1024 * 1024
    );

    /** A number, optional whitespace, and an optional unit. */
    private static final Pattern UNIT_VALUE = Pattern.compile("^([+-]?\\d+)\\s*([A-Za-z]*)$");

    private static long parseDuration(String propertyName, String sourceName, String value) {
        return parseUnitValue(propertyName, sourceName, value, "duration", DURATION_UNITS);
    }

    private static long parseSize(String propertyName, String sourceName, String value) {
        return parseUnitValue(propertyName, sourceName, value, "size", SIZE_UNITS);
    }

    /**
     * Parse `<number><unit>` into the canonical unit. The units are matched
     * case-sensitively, so `m` (minutes) and `MB` (megabytes) stay distinct and
     * a mistyped case is an error rather than a value that is wrong by a factor
     * of a thousand.
     */
    private static long parseUnitValue(
        String propertyName, String sourceName, String value, String typeName, Map<String, Long> units
    ) {
        Matcher matcher = UNIT_VALUE.matcher(value);
        if (!matcher.matches()) {
            throw new ConfigException(unitError(propertyName, sourceName, value, typeName, units));
        }
        String unit = matcher.group(2);
        long multiplier = unit.isEmpty()
            ? 1 // no unit means the canonical one
            : units.getOrDefault(unit, 0L);
        if (multiplier == 0) {
            throw new ConfigException(unitError(propertyName, sourceName, value, typeName, units));
        }
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
        } catch (ArithmeticException e) {
            String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
            throw new ConfigException(
                "value is too large for property `" + propertyName + "` (in " + source + "): " + value, e
            );
        }
    }

    /** The units are listed, since the error is the only place a reader looks. */
    private static String unitError(
        String propertyName, String sourceName, String value, String typeName, Map<String, Long> units
    ) {
        String source = sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file";
        return "error parsing the value of `" + propertyName + "` (in " + source + ") as type `"
            + typeName + "`: " + value
            + "\n\na " + typeName + " is a whole number with an optional unit, and the units are: "
            + units.keySet().stream().sorted().collect(Collectors.joining(", "))
            + "\nwith no unit meaning " + (units == DURATION_UNITS ? "milliseconds" : "bytes");
    }

    private static String handleEscapeSequences(String sourceName, String value) {
        // turn escaped backslashes into nulls temporarily, so they don't get
        // unescaped in the next step
        value = value.replaceAll("\\\\\\\\", "\0");
        // an escaped space is only meaningful as the first non-whitespace
        // character of a value, where it keeps the leading space from being
        // trimmed. anywhere else the space does not need to be escaped, so an
        // escaped space there is almost certainly a mistake - but it is only a
        // warning for now, since config sources hold arbitrary values that we
        // do not control. note that each item of a list, and each allowed
        // value, is its own value here (checking the last escaped space is
        // enough - if anything precedes it, whether that is an ordinary
        // character or an earlier escaped space, it is not at the start)
        int escapedSpaceIndex = value.lastIndexOf("\\ ");
        if (escapedSpaceIndex != -1 && !value.substring(0, escapedSpaceIndex).isBlank()) {
            logger.warn(
                "an escaped space is only meaningful at the start of a value, so it does nothing here (in {}): {}",
                sourceName != null ? "source `" + sourceName + "`" : "the `rwconfig` file",
                value.replace('\0', '\\')
            );
        }
        // handle escape sequences supported by java.util.Properties in rwconfig
        if (sourceName == null) { // this is the rwconfig file
            value = value.replaceAll("\\\\t", "\t");
            value = value.replaceAll("\\\\n", "\n");
            value = value.replaceAll("\\\\r", "\r");
            // handle unicode escape sequences
            Pattern pattern = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})");
            Matcher matcher = pattern.matcher(value);
            value = matcher.replaceAll(match -> String.valueOf((char) Integer.parseInt(match.group(1), 16)));
        }
        // handle other escape sequences
        value = value.replaceAll("\\\\,", ",");
        value = value.replaceAll("\\\\:", ":");
        value = value.replaceAll("\\\\ ", " ");
        value = value.replaceAll("\\\\e", "");
        value = value.replaceAll("\\\\\\]", "]");
        // throw an exception if there are any remaining unrecognized escape sequences
        int errorIndex = value.indexOf('\\');
        if ( errorIndex != -1) {
            if (errorIndex == value.length() - 1) {
                throw new ConfigException("invalid ending backslash in value: " + value);
            } else {
                throw new ConfigException(
                    "invalid escape sequence `\\" + value.charAt(errorIndex + 1)+ "` in value: " + value
                );
            }
        }
        // turn the nulls back into backslashes
        value = value.replaceAll("\0", "\\\\");
        return value;
    }

    private static SimpleConfigSourcePlugin loadPluginByModule(String name) {
        String shortName = name.endsWith(PLUGIN_TYPE_SUFFIX)
            ? name.substring(0, name.length() - PLUGIN_TYPE_SUFFIX.length())
            : null;
        String moduleName = shortName != null ? PLUGIN_MODULE_PREFIX + shortName : name;
        List<ServiceLoader.Provider<SimpleConfigSourcePlugin>> providers =
            ServiceLoader.load(SimpleConfigSourcePlugin.class).stream()
                .filter(provider -> provider.type().getModule().isNamed())
                .toList();
        return providers.stream()
            .filter(provider -> moduleName.equals(provider.type().getModule().getName()))
            .findFirst()
            .map(ServiceLoader.Provider::get)
            .orElseThrow(() -> new ConfigException(unknownSourceTypeMessage(name, shortName, providers)));
    }

    /**
     * Explain a source type that resolved to nothing. Forgetting the plugin's
     * jar, or putting it on the class path instead of the module path, are the
     * two ways this normally happens, and neither is obvious from the fact that
     * a module was not found - so the message says what to do about it, and
     * lists what was found, which separates "no plugins at all" from "every
     * plugin but this one".
     */
    private static String unknownSourceTypeMessage(
        String type, String shortName, List<ServiceLoader.Provider<SimpleConfigSourcePlugin>> found
    ) {
        StringBuilder message = new StringBuilder("no plugin provides config source type `" + type + "`");
        if (shortName != null) {
            message
                .append("\n\n`").append(type).append("` is a plugin, not a built-in source type, so it needs its")
                .append(" own jar:\n\n")
                .append("    <dependency>\n")
                .append("        <groupId>net.rabbitware.config</groupId>\n")
                .append("        <artifactId>plugin-").append(shortName).append("</artifactId>\n")
                // deliberately not the real version - a hard-coded one here
                // would go stale, and a plugin always tracks the core version
                .append("        <version>(same version as rwConfig)</version>\n")
                .append("    </dependency>\n\n")
                .append("and the jar has to be on the module path - plugins are not found on the class path.");
        }
        message.append("\n\nplugins found: ").append(
            found.isEmpty()
                ? "(none)"
                : found.stream()
                    .map(provider -> provider.type().getModule().getName())
                    .map(m -> m.startsWith(PLUGIN_MODULE_PREFIX)
                        ? m.substring(PLUGIN_MODULE_PREFIX.length()) + PLUGIN_TYPE_SUFFIX
                        : m)
                    .sorted()
                    .collect(Collectors.joining(", "))
        );
        message.append("\nbuilt-in source types: ").append(
            Stream.of(SourceType.values()).map(sourceType -> sourceType.name).collect(Collectors.joining(", "))
        );
        return message.toString();
    }

    private static String getPluginProperty(
        Map<String, String> configProperties,
        Map<String, Map<String, String>> configSources,
        String configPrefix,
        String sourceName,
        String propertyName,
        boolean required
    ) {
        String fullPropertyName = configPrefix + sourceName + "." + propertyName;
        String propertyValue = configProperties.get(fullPropertyName);
        if (required && (propertyValue == null || propertyValue.isEmpty())) {
            throw new ConfigException(
                "missing library setting for config source `" + sourceName + "`: " + fullPropertyName
            );
        }
        // A value of `<<` is a deferred value: the real value lives in a config
        // source that was loaded before this one. This is useful for sensitive
        // information such as passwords, which should not be stored in the
        // `rwconfig` file. The name searched for is the same as the fully
        // qualified name of the property here - for example, a setting named
        // `<configPrefix>jdbc.password` is looked up under that same name in the
        // sources already loaded.
        if (propertyValue != null && DEFERRED_VALUE.equals(propertyValue.trim())) {
            propertyValue = configSources.values().stream()
                .filter(map -> map.containsKey(fullPropertyName))
                .findFirst()
                .map(map -> map.get(fullPropertyName))
                .orElseThrow(() -> new ConfigException(
                    "library setting `" + fullPropertyName + "` is set to `" + DEFERRED_VALUE
                    + "`, but no config source loaded before `" + sourceName + "` contains this property"
                ));
        }
        return propertyValue;
    }



    //
    // nested classes
    //

    private static final class CommandLineProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        /**
         * Every argument that looks like a property assignment is collected,
         * not just the ones that were declared. Unlike the environment or the
         * system properties - which are full of entries that have nothing to do
         * with this application - every command line argument was typed
         * deliberately, for this program, so an argument that matches no
         * declaration is far more likely to be a typo than a coincidence.
         * Collecting them all lets the usual unknown-property check see them,
         * which means `ignoreUnknownProperties` turns this off in the same way
         * it does for every other config source.
         *
         * <p>An argument only counts as an assignment if its name is a legal
         * property name, so an application's own flags and positional arguments
         * (`--verbose`, `input.txt`, `-n=3`) are left alone. The library's own
         * arguments are skipped too.
         */
        private CommandLineProperties(String[] args, String configPrefix) {
            if (args == null) { // if no args were provided, just return an empty map
                return;
            }
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                Matcher matcher = COMMAND_LINE_ASSIGNMENT.matcher(arg);
                if (!matcher.matches()) { // not a property assignment - leave it for the application
                    continue;
                }
                String name = matcher.group(1);
                // the library's own arguments are not application properties
                if (name.equals(CONFIG_FILE_PATH_PROPERTY) || name.startsWith(configPrefix)) {
                    continue;
                }
                // first occurrence wins, matching `getCommandLineArgument`
                map.putIfAbsent(name, matcher.group(2));
            }
        }

        @Override public String get(Object key) { return key instanceof String name ? map.get(name) : null; }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Set<String> keySet() { return Set.copyOf(map.keySet()); }
        @Override public Collection<String> values() { return List.copyOf(map.values()); }
        @Override public Set<Entry<String, String>> entrySet() { return Set.copyOf(map.entrySet()); }
        // other methods are not implemented, as they are not needed
        @Override public String put(String key, String value) { throw new UnsupportedOperationException(); }
        @Override public String remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends String, ? extends String> m) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SystemProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        private SystemProperties(Map<String, PropertyInfo> propertyInfoMap) {
            propertyInfoMap.keySet().stream()
                .forEach(name -> {
                    String value = getSystemProperty(name);
                    if (value != null) {
                        map.put(name, value);
                    }
                });
        }

        @Override public String get(Object key) { return key instanceof String name ? map.get(name) : null; }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Set<String> keySet() { return Set.copyOf(map.keySet()); }
        @Override public Collection<String> values() { return List.copyOf(map.values()); }
        @Override public Set<Entry<String, String>> entrySet() { return Set.copyOf(map.entrySet()); }
        // other methods are not implemented, as they are not needed
        @Override public String put(String key, String value) { throw new UnsupportedOperationException(); }
        @Override public String remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends String, ? extends String> m) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EnvironmentProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        private EnvironmentProperties(Map<String, PropertyInfo> propertyInfoMap) {
            propertyInfoMap.keySet().stream()
                .forEach(name -> {
                    String value = getEnvironmentVariable(name);
                    if (value != null) {
                        map.put(name, value);
                    }
                });
        }

        @Override public String get(Object key) { return key instanceof String name ? map.get(name) : null; }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Set<String> keySet() { return Set.copyOf(map.keySet()); }
        @Override public Collection<String> values() { return List.copyOf(map.values()); }
        @Override public Set<Entry<String, String>> entrySet() { return Set.copyOf(map.entrySet()); }
        // other methods are not implemented, as they are not needed
        @Override public String put(String key, String value) { throw new UnsupportedOperationException(); }
        @Override public String remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends String, ? extends String> m) {
            throw new UnsupportedOperationException();
        }
    }
}
