package net.rabbitware.config;

/**
 * A type as it may be written in the {@code rwconfig} file.
 *
 * <p>This is deliberately not {@link RuntimeType}, which is the type a
 * property has at run time and is what {@link Config#getType} returns. The two
 * lists are nearly the same, but not quite: {@code duration} and {@code size}
 * can be written in the file and are parsed into a {@code long}, so they never
 * exist as a run-time type. Keeping them out of the public enum means a
 * {@code switch} over {@code getType()} stays exhaustive without handling cases
 * that cannot occur.
 */
enum DeclaredType {
    BOOLEAN("boolean", RuntimeType.BOOLEAN),
    INT("int", RuntimeType.INT),
    LONG("long", RuntimeType.LONG),
    DOUBLE("double", RuntimeType.DOUBLE),
    STRING("string", RuntimeType.STRING),
    BIG_INTEGER("bigInteger", RuntimeType.BIG_INTEGER),
    BIG_DECIMAL("bigDecimal", RuntimeType.BIG_DECIMAL),
    DURATION("duration", RuntimeType.LONG),
    SIZE("size", RuntimeType.LONG),
    TIMESTAMP("timestamp", RuntimeType.LONG),

    BOOLEAN_LIST("booleanList", RuntimeType.BOOLEAN_LIST),
    INT_LIST("intList", RuntimeType.INT_LIST),
    LONG_LIST("longList", RuntimeType.LONG_LIST),
    DOUBLE_LIST("doubleList", RuntimeType.DOUBLE_LIST),
    STRING_LIST("stringList", RuntimeType.STRING_LIST),
    BIG_INTEGER_LIST("bigIntegerList", RuntimeType.BIG_INTEGER_LIST),
    BIG_DECIMAL_LIST("bigDecimalList", RuntimeType.BIG_DECIMAL_LIST),
    DURATION_LIST("durationList", RuntimeType.LONG_LIST),
    SIZE_LIST("sizeList", RuntimeType.LONG_LIST),
    TIMESTAMP_LIST("timestampList", RuntimeType.LONG_LIST);

    private static final java.util.Map<String, DeclaredType> nameToTypeMap = new java.util.HashMap<>();

    public final String name;
    public final RuntimeType runtimeType;

    static {
        for (DeclaredType type : DeclaredType.values()) {
            nameToTypeMap.put(type.name.toLowerCase(), type);
        }
    }

    public static DeclaredType fromString(String type) {
        DeclaredType declaredType = nameToTypeMap.get(type.toLowerCase());
        if (declaredType != null) {
            return declaredType;
        }
        throw new Config.ConfigException("unknown property type: " + type);
    }

    private DeclaredType(String name, RuntimeType runtimeType) {
        this.name = name;
        this.runtimeType = runtimeType;
    }
}
