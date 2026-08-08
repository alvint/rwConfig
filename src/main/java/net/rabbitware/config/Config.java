package net.rabbitware.config;
import java.util.List;

public interface Config {
    /**
     * Return {@code true} if the config contains a property with the given
     * name.
     *
     * @param name
     * the name of the property
     * @return
     * {@code true} if the config contains a property with the given name
     */
    public boolean has(String name);

    /**
     * Get the type of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the type of the property with the given name, as a string
     * @throws ConfigException
     * if the config does not contain a property with the given name
     */
    public PropertyType getType(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code boolean})
     */
    public boolean getBoolean(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code int})
     */
    public int getInt(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code long})
     */
    public long getLong(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code double})
     */
    public double getDouble(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code String})
     */
    public String getString(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type
     * ({@code List<Boolean>})
     */
    public List<Boolean> getBooleanList(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type
     * ({@code List<Integer>})
     */
    public List<Integer> getIntList(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code List<Long>})
     */
    public List<Long> getLongList(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code List<Double>})
     */
    public List<Double> getDoubleList(String name) throws ConfigException;

    /**
     * Get the value of the property with the given name.
     *
     * @param name
     * the name of the property
     * @return
     * the value of the property with the given name
     * @throws ConfigException
     * if the config does not contain a property with the given name, or if the
     * property is not compatible with the specified type ({@code List<String>})
     */
    public List<String> getStringList(String name) throws ConfigException;

    // abbreviated method names for convenience

    /// same as {@link #getBoolean(String)}
    public boolean getb(String name) throws ConfigException;
    /// same as {@link #getInt(String)}
    public int geti(String name) throws ConfigException;
    /// same as {@link #getLong(String)}
    public long getl(String name) throws ConfigException;
    /// same as {@link #getDouble(String)}
    public double getd(String name) throws ConfigException;
    /// same as {@link #getString(String)}
    public String gets(String name) throws ConfigException;

    /// same as {@link #getBooleanList(String)}
    public List<Boolean> getbl(String name) throws ConfigException;
    /// same as {@link #getIntList(String)}
    public List<Integer> getil(String name) throws ConfigException;
    /// same as {@link #getLongList(String)}
    public List<Long> getll(String name) throws ConfigException;
    /// same as {@link #getDoubleList(String)}
    public List<Double> getdl(String name) throws ConfigException;
    /// same as {@link #getStringList(String)}
    public List<String> getsl(String name) throws ConfigException;


    //
    // nested classes
    //

    public static enum PropertyType {
        BOOLEAN("boolean"), INT("int"), LONG("long"), DOUBLE("double"), STRING("string"),
        BOOLEAN_LIST("booleanList"), INT_LIST("intList"), LONG_LIST("longList"), DOUBLE_LIST("doubleList"),
        STRING_LIST("stringList");

        public final String name;
        private static final java.util.Map<String, PropertyType> nameToTypeMap = new java.util.HashMap<>();

        static {
            for (PropertyType type : PropertyType.values()) {
                nameToTypeMap.put(type.name.toLowerCase(), type);
            }
        }

        public static PropertyType fromString(String type) {
            PropertyType propertyType = nameToTypeMap.get(type.toLowerCase());
            if (propertyType != null) {
                return propertyType;
            }
            throw new ConfigException("unknown property type: " + type);
        }

        private PropertyType(String name) {
            this.name = name;
        }
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PropertyNotFoundException extends ConfigException {
        public PropertyNotFoundException(String propertyName) {
            super("property `" + propertyName + "` not found");
        }
    }

    public static class IncorrectTypeException extends ConfigException {
        public IncorrectTypeException(String propertyName, String expectedType, String actualType) {
            super("property `" + propertyName + "` is of type `" + actualType + "` - not type `" + expectedType + "`");
        }
    }
}
