package net.rabbitware.config;

/**
 * Represents the runtime type of a property in the configuration.
 */
public enum RuntimeType {
    BOOLEAN("boolean"), INT("int"), LONG("long"), DOUBLE("double"), STRING("string"), BIG_INTEGER("bigInteger"),
    BIG_DECIMAL("bigDecimal"),
    BOOLEAN_LIST("booleanList"), INT_LIST("intList"), LONG_LIST("longList"), DOUBLE_LIST("doubleList"),
    STRING_LIST("stringList"), BIG_INTEGER_LIST("bigIntegerList"), BIG_DECIMAL_LIST("bigDecimalList");

    public final String name;
    private static final java.util.Map<String, RuntimeType> nameToTypeMap = new java.util.HashMap<>();

    static {
        for (RuntimeType type : RuntimeType.values()) {
            nameToTypeMap.put(type.name.toLowerCase(), type);
        }
    }

    public static RuntimeType fromString(String type) {
        RuntimeType propertyType = nameToTypeMap.get(type.toLowerCase());
        if (propertyType != null) {
            return propertyType;
        }
        throw new Config.ConfigException("unknown property type: " + type);
    }

    private RuntimeType(String name) {
        this.name = name;
    }
}
