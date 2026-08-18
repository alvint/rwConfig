package net.rabbitware.config;

import net.rabbitware.config.Config.PropertyType;

/**
 * A type as it may be written in the {@code rwconfig} file.
 *
 * <p>This is deliberately not {@link PropertyType}, which is the type a
 * property has at run time and is what {@link Config#getType} returns. The two
 * lists are nearly the same, but not quite: {@code duration} and {@code size}
 * can be written in the file and are parsed into a {@code long}, so they never
 * exist as a run-time type. Keeping them out of the public enum means a
 * {@code switch} over {@code getType()} stays exhaustive without handling cases
 * that cannot occur.
 */
enum DeclaredType {
    BOOLEAN("boolean"), INT("int"), LONG("long"), DOUBLE("double"), STRING("string"),
    DURATION("duration"), SIZE("size"), TIMESTAMP("timestamp"),
    BOOLEAN_LIST("booleanList"), INT_LIST("intList"), LONG_LIST("longList"), DOUBLE_LIST("doubleList"),
    STRING_LIST("stringList"), DURATION_LIST("durationList"), SIZE_LIST("sizeList"),
    TIMESTAMP_LIST("timestampList");

    public final String name;
    private static final java.util.Map<String, DeclaredType> nameToTypeMap = new java.util.HashMap<>();

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

    private DeclaredType(String name) {
        this.name = name;
    }
}
