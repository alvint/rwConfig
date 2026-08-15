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
    public static final String DEFAULT_CONFIG_ROOT = "rwc.";
    public static final String CONFIG_ROOT_PROPERTY = DEFAULT_CONFIG_ROOT + "root";

    /**
     * This special value tells the library to look for the real value of this
     * property in a previously loaded config source.
     */
    public static final String BACKREFERENCE_VALUE = "<<";

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
        // figure out the config root - that is, the prefix that all config
        // properties must start with
        String configRoot = configFile.stream()
            .map(s -> s.replaceAll("^\\s*[!#].*$", "")) // remove comments
            .filter(s -> !s.trim().isEmpty()) // remove empty lines
            .map(s -> s.split("=", 2))
            .filter(parts -> parts.length == 2)
            .filter(parts -> parts[0].trim().equals(CONFIG_ROOT_PROPERTY))
            .map(parts -> parts[1].trim())
            .findFirst()
            .orElse(DEFAULT_CONFIG_ROOT);
        // parse the config setup and property info from the config file
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
                if (name.startsWith(configRoot) || name.equals(CONFIG_ROOT_PROPERTY)) { // config setup line
                    if (type != null && !type.isEmpty()) {
                        throw new ConfigException("invalid config line (config setup lines cannot have a type): " + s);
                    }
                    if (allowedValues != null && !allowedValues.isEmpty()) {
                        throw new ConfigException(
                            "invalid config line (config setup lines cannot have allowed values): " + s
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
            "rwconfig file loaded successfully with {} config setup lines and {} property info lines",
            configProperties.size(), propertyInfoMap.size())
        ;

        // get all of the config sources
        Map<String, Map<String, String>> configSources = new LinkedHashMap<>();
        String sourceNames = configProperties.get(configRoot + "sources");
        if (sourceNames == null || sourceNames.isEmpty()) {
            throw new ConfigException("missing config property: " + configRoot + "sources");
        }
        Stream.of(sourceNames.trim().split("\\s*,\\s*", -1))
            .forEach(sourceName -> {
                if (sourceName.isEmpty()) {
                    throw new ConfigException(
                        "invalid `" + configRoot + "sources` property (empty source name): " + sourceNames
                    );
                }
                if (configSources.containsKey(sourceName)) {
                    throw new ConfigException("duplicate config source name: " + sourceName);
                }
                logger.debug("loading config source: {}", sourceName);
                String type = getPluginProperty(configProperties, configSources, configRoot, sourceName, "type", true);
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
                    // get the properties for the plugin from the config file
                    Map<String, String> properties = new HashMap<>();
                    requiredProperties.stream()
                        .forEach(propertyName -> {
                            String propertyValue = getPluginProperty(
                                configProperties, configSources, configRoot, sourceName, propertyName, true
                            );
                            properties.put(propertyName, propertyValue);
                        });
                    optionalProperties.stream()
                        .forEach(propertyName -> {
                            String propertyValue = getPluginProperty(
                                configProperties, configSources, configRoot, sourceName, propertyName, false
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
                            configSources.put(sourceName, new CommandLineProperties(commandLineArgs, propertyInfoMap));
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
                            String location = getPluginProperty(configProperties, configSources, configRoot, sourceName, "location", true);
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
                            String dirPath = getPluginProperty(configProperties, configSources, configRoot, sourceName, "path", true);
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
                    .getOrDefault(configRoot + sourceName + ".ignoreUnknownProperties", "false")
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
        // config sources, but have a default value defined in the config file
        //
        // if a property has no default value defined in the config file, and is 
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
                        + "` is not set by any config source, and has no default value defined in the config file"
                    );
                }
            });
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
            // load the config file from the location
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
            // try to load the config file from the classpath first, then the
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
                        String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
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
                        String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
                        throw new ConfigException(
                            "value is not allowed for property `" + propertyName + "` (in " + source + "): "
                            + unescapedValueString
                        );
                    }
                    // value is allowed; return it
                    yield new Value.Integer(value);
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
                        String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
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
                        String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
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
                        String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
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
            String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
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
                sourceName != null ? "source `" + sourceName + "`" : "config file",
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
        String moduleName;
        if (name.endsWith(".plugin")) {
            moduleName = "net.rabbitware.config.plugin." + name.substring(0, name.length() - 7);
        } else {
            moduleName = name;
        }
        return ServiceLoader.load(SimpleConfigSourcePlugin.class).stream()
            .filter(provider -> {
                Module m = provider.type().getModule();
                return m.isNamed() && moduleName.equals(m.getName());
            })
            .findFirst()
            .map(ServiceLoader.Provider::get)
            .orElseThrow(() -> new ConfigException(
                "no module that provides `SimpleConfigSourcePlugin` was found by that name: " + moduleName)
            );
    }

    private static String getPluginProperty(
        Map<String, String> configProperties,
        Map<String, Map<String, String>> configSources,
        String configRoot,
        String sourceName,
        String propertyName,
        boolean required
    ) {
        String fullPropertyName = configRoot + sourceName + "." + propertyName;
        String propertyValue = configProperties.get(fullPropertyName);
        if (required && (propertyValue == null || propertyValue.isEmpty())) {
            throw new ConfigException("missing required config property:" + fullPropertyName);
        }
        // Check for the special value to indicate that the property should be
        // retrieved from another config source that was loaded before this one.
        // This is useful for sensitive information such as passwords, which
        // should not be stored in the config file. The name searched for in the
        // previously loaded config sources is the same as the fully qualified
        // name of the property here. For example, if the property name here is
        // `<configRoot>jdbc.password`, the plugin will look for this property
        // name in the previously loaded config sources.
        if (propertyValue != null && BACKREFERENCE_VALUE.equals(propertyValue.trim())) {
            propertyValue = configSources.values().stream()
                .filter(map -> map.containsKey(fullPropertyName))
                .findFirst()
                .map(map -> map.get(fullPropertyName))
                .orElseThrow(() -> new ConfigException(
                    "config property `" + fullPropertyName + "` is set to the special backreference value `"
                    + BACKREFERENCE_VALUE + "`, but no previously loaded config source contains this property"
                ));
        }
        return propertyValue;
    }



    //
    // nested classes
    //

    private static final class CommandLineProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        private CommandLineProperties(String[] args, Map<String, PropertyInfo> propertyInfoMap) {
            if (args == null) { // if no args were provided, just return an empty map
                return;
            }
            propertyInfoMap.keySet().stream()
                .forEach(name -> {
                    String value = getCommandLineArgument(name, args);
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
