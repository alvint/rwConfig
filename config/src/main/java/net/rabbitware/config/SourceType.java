package net.rabbitware.config;

enum SourceType {
    COMMAND_LINE_ARGUMENTS("commandLineArguments"),
    SYSTEM_PROPERTIES("systemProperties"),
    ENVIRONMENT_VARIABLES("environmentVariables"),
    FILE("file"),
    CLASSPATH("classpath"),
    URL("url"),
    DIRECTORY("directory"),
    DATABASE("database");

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
        throw new Config.ConfigException("unknown source type: " + type);
    }

    private SourceType(String name) {
        this.name = name;
    }
}
