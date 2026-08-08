package net.rabbitware.config;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.rabbitware.config.Config.ConfigException;
import net.rabbitware.config.Config.PropertyType;

public class ConfigFactory {
    public static final String CONFIG_FILE_PATH_PROPERTY = "rw.config.path";
    public static final String DEFAULT_CONFIG_FILE_PATH = "rwconfig";

    public static Config create() throws ConfigException {
        return create(null);
    }

    public static Config create(String[] commandLineArgs) throws ConfigException {
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
        // 2. the allowed values of the property (optional)
        //    - if the allowed values are not specified, any value is allowed
        //    - leading whitespace is ignored, but trailing whitespace is kept
        //      to allow for values that end with whitespace, such as "foo " and
        //      "bar "
        //      - to create a value that starts with whitespace, escape the
        //        first space with a backslash
        // 3. the name of the property (required)
        //    - leading and trailing whitespace is ignored
        // 4. the value of the property (optional)
        //    - leading whitespace is ignored, but trailing whitespace is kept
        //      to allow for values that end with whitespace, such as "foo " and
        //      "bar "
        //      - to create a value that starts with whitespace, escape the
        //        first space with a backslash
        //
        // If you find a valid config line that is not matched by this regex,
        // please report it as a bug.
        String types = Stream.of(PropertyType.values())
            .map(t -> t.name)
            .reduce((a, b) -> a + "|" + b)
            .orElse("");
        var pattern = Pattern.compile(
"^[^\\S\\n\\r]*((?i:{types}))?[^\\S\\n\\r]*(?:\\[\\s*((?:(?:(?:\\\\[\\\\\\]\\[,: ]|[^\\\\\\]\\[,:\\s])(?:\\\\[\\\\\\]\\[,:]|[^\\\\\\]\\[,:])*)(?::\\s*(?:(?:\\\\[\\\\\\]\\[,: ]|[^\\\\\\]\\[,:\\s])(?:\\\\[\\\\\\]\\[,:]|[^\\\\\\]\\[,:])*))?)(?:,\\s*(?:(?:(?:\\\\[\\\\\\]\\[,: ]|[^\\\\\\]\\[,:\\s])(?:\\\\[\\\\\\]\\[,:]|[^\\\\\\]\\[,:])*)(?::\\s*(?:(?:\\\\[\\\\\\]\\[,: ]|[^\\\\\\]\\[,:\\s])(?:\\\\[\\\\\\]\\[,:]|[^\\\\\\]\\[,:])*))?))*)\\])?\\s*([A-Za-z][\\w\\.]*)\\s*(?:=[^\\S\\n\\r]*([^\\n\\r]*))?$"
.replace("{types}", types));

        loadConfigFile(commandLineArgs).stream()
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
                if (name.startsWith("config.")) { // config setup line
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
                    propertyInfoMap.put(name, getPropertyInfo(name, type, allowedValues, defaultValue));
                }
            })
        ;

        // get all of the config sources
        Map<String, Map<String, String>> configSources = new LinkedHashMap<>();
        String sourceNames = configProperties.get("config.sources");
        if (sourceNames == null || sourceNames.isEmpty()) {
            throw new ConfigException("missing config property: config.sources");
        }
        Stream.of(sourceNames.trim().split("\\s*,\\s*", -1))
            .forEach(sourceName -> {
                if (sourceName.isEmpty()) {
                    throw new ConfigException("invalid `config.sources` property (empty source name): " + sourceNames);
                }
                if (configSources.containsKey(sourceName)) {
                    throw new ConfigException("duplicate config source name: " + sourceName);
                }
                String sourceType = configProperties.get("config." + sourceName + ".type");
                if (sourceType == null || sourceType.isEmpty()) {
                    throw new ConfigException("missing config property: config." + sourceName + ".type");
                }
                switch (SourceType.fromString(sourceType)) {
                    case COMMAND_LINE_ARGUMENTS -> {
                        if (commandLineArgs == null) {
                            throw new ConfigException(
                                "config source `" + sourceName + "` is of type `commandLineArguments`, "
                                + "but no command line arguments were provided to the config system"
                            );
                        }
                        configSources.put(sourceName, new CommandLineProperties(commandLineArgs, propertyInfoMap));
                    }
                    case SYSTEM_PROPERTIES -> {
                        configSources.put(sourceName, new SystemProperties(propertyInfoMap));
                    }
                    case ENVIRONMENT_VARIABLES -> {
                        configSources.put(sourceName, new EnvironmentProperties(propertyInfoMap));
                    }
                    case FILE -> {
                        String filePath = configProperties.get("config." + sourceName + ".path");
                        if (filePath == null || filePath.isEmpty()) {
                            throw new ConfigException("missing config property: config." + sourceName + ".path");
                        }
                        Properties properties = new Properties();
                        try (
                            var reader = new InputStreamReader(
                                Files.newInputStream(Path.of(filePath)),
                                StandardCharsets.UTF_8
                            )
                        ) {
                            properties.load(reader);
                            configSources.put(sourceName, toMap(properties));
                        } catch (IOException e) {
                            throw new ConfigException(
                                "error reading config source file for `" + sourceName + "`: " + filePath, e
                            );
                        }
                    }
                    case CLASSPATH -> {
                        String path = configProperties.get("config." + sourceName + ".path");
                        if (path == null || path.isEmpty()) {
                            throw new ConfigException("missing config property: config." + sourceName + ".path");
                        }
                        Properties properties = new Properties();
                        var url = Thread.currentThread().getContextClassLoader().getResource(path);
                        if (url == null) {
                            throw new ConfigException(
                                "config source file not found on classpath for `" + sourceName + "`: " + path
                            );
                        }
                        try (var reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
                            properties.load(reader);
                            configSources.put(sourceName, toMap(properties));
                        } catch (IOException e) {
                            throw new ConfigException(
                                "error reading config source file from classpath for `" + sourceName + "`: " + path, e
                            );
                        }
                    }
                    case URL -> {
                        String url = configProperties.get("config." + sourceName + ".url");
                        if (url == null || url.isEmpty()) {
                            throw new ConfigException("missing config property: config." + sourceName + ".url");
                        }
                        Properties properties = new Properties();
                        try (
                            var reader = new InputStreamReader(
                                java.net.URI.create(url).toURL().openStream(), StandardCharsets.UTF_8
                            )
                        ) {
                            properties.load(reader);
                            configSources.put(sourceName, toMap(properties));
                        } catch (IOException e) {
                            throw new ConfigException(
                                "error reading config source from URL for `" + sourceName + "`: " + url, e
                            );
                        }
                    }
                    // TODO
                    case DATABASE -> throw new ConfigException(
                        "source type for `" + sourceName + "` is not implemented yet: "
                        + SourceType.fromString(sourceType)
                    );
                }
            });

        // create the config object and set its values
        var config = new ConfigImpl();
        configSources.entrySet().stream()
            .forEach(entry -> {
                String sourceName = entry.getKey();
                Map<String, String> properties = entry.getValue();
                properties.keySet().stream()
                    .filter(s -> !config.has(s))
                    .forEach(name -> {
                        String valueString = properties.get(name);
                        if (!propertyInfoMap.containsKey(name)) {
                            throw new ConfigException(
                                "property `"
                                    + name
                                    + "` is not defined in the config file, but is present in a config source `"
                                    + sourceName
                                    + "`"
                            );
                        }
                        PropertyInfo propertyInfo = propertyInfoMap.get(name);
                        Value value = parseValue(
                            sourceName, name, valueString, propertyInfo.propertyType, propertyInfo.allowedValues
                        );
                        config.add(name, value);
                    });
            });

        // set the default values for any properties that were not set by the
        // config sources, but have a default value defined in the config file
        //
        // if a property has no default value defined in the config file, and is 
        // not set by any config source, throw a ConfigException
        propertyInfoMap.entrySet().stream()
            .filter(entry -> !config.has(entry.getKey()))
            .forEach(entry -> {
                String name = entry.getKey();
                PropertyInfo propertyInfo = entry.getValue();
                if (propertyInfo.defaultValue != null) {
                    config.add(name, propertyInfo.defaultValue);
                } else {
                    throw new ConfigException(
                        "property `" + name
                        + "` is not set by any config source, and has no default value defined in the config file"
                    );
                }
            });
        return config;
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
        // try to get the config file path from the command line arguments
        String configFilePath = getCommandLineArgument(CONFIG_FILE_PATH_PROPERTY, commandLineArgs);

        // try to get the config file path from the system properties
        if (configFilePath == null) {
            configFilePath = getSystemProperty(CONFIG_FILE_PATH_PROPERTY);
        }

        // try to get the config file path from the environment variables
        if (configFilePath == null) {
            configFilePath = getEnvironmentVariable(CONFIG_FILE_PATH_PROPERTY);
        }

        // use the default config file path if none was specified
        if (configFilePath == null) {
            configFilePath = DEFAULT_CONFIG_FILE_PATH;
        }

        // load the config file
        Path path = Path.of(configFilePath);
        if (Files.exists(path)) { // try to load the config file from the filesystem
            try {
                return Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ConfigException("error reading configuration file: " + configFilePath, e);
            }
        } else { // try to load the config file from the classpath
            try (
                InputStream inputStream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(configFilePath)
            ) {
                if (inputStream != null) {
                    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
                }
            } catch (IOException e) {
                throw new ConfigException("error reading configuration file from classpath: " + configFilePath, e);
            }
        }
        throw new ConfigException("configuration file not found: " + configFilePath);
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
            switch (propertyType) {
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
                    return new Value.Boolean(value);
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
                    return new Value.Integer(value);
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
                    return new Value.Long(value);
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
                    return new Value.Double(value);
                }
                case STRING -> {
                    // handle escape sequences

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
                    return new Value.String(unescapedValueString);
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
                    return new Value.BooleanList(list);
                }
                case INT_LIST -> {
                    List<Value.Integer> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Integer)
                                parseValue(sourceName, propertyName, s, PropertyType.INT, allowedValues))
                            .toList();
                    return new Value.IntegerList(list);
                }
                case LONG_LIST -> {
                    List<Value.Long> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Long)
                                parseValue(sourceName, propertyName, s, PropertyType.LONG, allowedValues))
                            .toList();
                    return new Value.LongList(list);
                }
                case DOUBLE_LIST -> {
                    List<Value.Double> list = valueString.isEmpty()
                        ? List.of()
                        : Stream.of(valueString.split("(?<!\\\\),", -1))
                            .map(s ->(Value.Double)
                                parseValue(sourceName, propertyName, s, PropertyType.DOUBLE, allowedValues))
                            .toList();
                    return new Value.DoubleList(list);
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
                    return new Value.StringList(list);
                }
            }
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            String source = sourceName != null ? "source `" + sourceName + "`" : "config file";
            throw new ConfigException(
                "error parsing the value of `" + propertyName + "` (in " + source + ") as type `" + propertyType.name + "`: "+ valueString,
                e
            );
        }
        // TODO:
        // The code above is an exhaustive switch with a `return` on all
        // branches, so I'm not sure why I need to throw an exception here. This
        // line should never be reached but the compiler is complaining without
        // it.
        throw new ConfigException("unsupported property type: " + propertyType.name);
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
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|\\.", "_")
                .toUpperCase();
            value = System.getenv(envName);
        }
        return value;
    }

    private static String handleEscapeSequences(String sourceName, String value) {
        // turn escaped backslashes into nulls temporarily, so they don't get
        // unescaped in the next step
        value = value.replaceAll("\\\\\\\\", "\0");
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
