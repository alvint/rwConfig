package net.rabbitware.config;

enum SourceType {
    COMMAND_LINE_ARGUMENTS("commandLineArguments"),
    SYSTEM_PROPERTIES("systemProperties"),
    ENVIRONMENT_VARIABLES("environmentVariables"),
    PROPERTIES("properties"),
    DIRECTORY("directory");

    public final String name;
    private static final java.util.Map<String, SourceType> nameToTypeMap = new java.util.HashMap<>();

    static {
        for (SourceType type : SourceType.values()) {
            nameToTypeMap.put(type.name, type);
        }
    }

    public static SourceType fromString(String type) {
        SourceType sourceType = nameToTypeMap.get(type);
        if (sourceType != null) {
            return sourceType;
        }
        throw new Config.ConfigException(
            "unknown source type: " + type
            + "\n\nbuilt-in source types: " + validTypes()
            + "\na plugin source type contains a dot, such as `json.plugin`"
        );
    }

    private SourceType(String name) {
        this.name = name;
    }

    /** The type names a `rwconfig` file can use, for error messages. */
    private static String validTypes() {
        StringBuilder types = new StringBuilder();
        for (SourceType sourceType : values()) {
            types.append(types.isEmpty() ? "" : ", ").append(sourceType.name);
        }
        return types.toString();
    }
}
