package net.rabbitware.config;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface Config {
    /**
     * Get the name of this Config object.
     *
     * @return
     * the name of this Config object
     */
    public String getName();

    /**
     * Return a set of all property names in the config.
     *
     * @return
     * a set of all property names in the config
     */
    public Set<String> getPropertyNames();

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

    /** same as {@link #getBoolean(String)} */
    public boolean getb(String name) throws ConfigException;
    /** same as {@link #getInt(String)} */
    public int geti(String name) throws ConfigException;
    /** same as {@link #getLong(String)} */
    public long getl(String name) throws ConfigException;
    /** same as {@link #getDouble(String)} */
    public double getd(String name) throws ConfigException;
    /** same as {@link #getString(String)} */
    public String gets(String name) throws ConfigException;

    /** same as {@link #getBooleanList(String)} */
    public List<Boolean> getbl(String name) throws ConfigException;
    /** same as {@link #getIntList(String)} */
    public List<Integer> getil(String name) throws ConfigException;
    /** same as {@link #getLongList(String)} */
    public List<Long> getll(String name) throws ConfigException;
    /** same as {@link #getDoubleList(String)} */
    public List<Double> getdl(String name) throws ConfigException;
    /** same as {@link #getStringList(String)} */
    public List<String> getsl(String name) throws ConfigException;

    /**
     * Check if change detection is enabled for this configuration.
     *
     * @return
     * {@code true} if change detection is enabled, {@code false} otherwise
     */
    public boolean isChangeDetectionEnabled();

    /**
     * Add a change listener for all config sources.
     *
     * @param listenerName
     * the name of the listener
     * @param listener
     * the change listener to add
     */
    public void addChangeListener(String listenerName, ChangeListener listener);

    /**
     * Remove the change listener with the given name.
     *
     * @param listenerName
     * the name of the listener to remove
     */
    public void removeChangeListener(String listenerName);

    /**
     * Add a change listener for the specified config source.
     * 
     * @param sourceName
     * the name of the configuration source
     * @param listenerName
     * the name of the listener
     * @param listener
     * the change listener to add
     */
    public void addChangeListener(String sourceName, String listenerName, ChangeListener listener);

    /**
     * Remove the change listener with the given name for the specified config
     * source.
     *
     * @param sourceName
     * the name of the config source
     * @param listenerName
     * the name of the listener to remove
     */
    public void removeChangeListener(String sourceName, String listenerName);

    /**
     * Discard this configuration. This stops any change detection and releases
     * associated resources.
     */
    public void discard();

    //
    // nested classes
    //

    /**
     * Represents the type of a property in the configuration.
     * This includes primitive types like BOOLEAN, INT, LONG, DOUBLE, STRING,
     * as well as their corresponding list types.
     */
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

    /**
     * A global holder for one {@code Config}, for applications that would
     * rather not thread the instance through every constructor.
     *
     * <p><b>Take the instance once and keep it.</b> {@link #get()} is only a
     * volatile read, so it is not expensive, but calling it for every property
     * read is still the wrong way to go. A {@code Config} is an immutable
     * snapshot, and holding one is what guarantees everything you read came
     * from the same configuration. Code that calls {@code get()} per read
     * gives that up; if the instance is replaced in between, it can see one
     * property from the old configuration and the next from the new one.
     *
     * <p>Passing the {@code Config} into constructors remains the better
     * approach where it is practical, since it makes the dependency visible
     * and the class testable without touching global state. This holder is the
     * pragmatic alternative and not the recommended one.
     *
     * <p>The instance may only be set once. Replacing it is a separate,
     * deliberately named operation, so that two components each initializing
     * the library is an error rather than a silent race.
     */
    public static class Instance {
        /**
         * A lock of this class's own rather than its monitor, so that nothing
         * outside can take part in it. It guards the writes only - reads never
         * take it.
         */
        private static final Object LOCK = new Object();

        /**
         * Volatile rather than lock-guarded, so that {@link #get()} - which
         * runs on every access - costs a plain read. Writes are rare and take
         * {@link #LOCK}; without it, the check and the assignment in
         * {@link #set(Config)} would be two steps that another thread could
         * interleave, and "set once" would only hold most of the time.
         */
        private static volatile Config instance;

        /**
         * Set the instance. This may be done only once: a second call is an
         * error, since it almost always means two parts of an application each
         * believe they are responsible for initializing the config. To swap in
         * a new configuration deliberately, use {@link #replace(Config)}.
         *
         * @param config
         * the config instance to set
         * @throws ConfigException
         * if an instance has already been set, or {@code config} is null
         */
        public static void set(Config config) {
            if (config == null) {
                throw new ConfigException("the config instance cannot be null");
            }
            synchronized (LOCK) {
                if (instance != null) {
                    throw new ConfigException(
                        "the config instance has already been set - use `replace` to swap in a new one"
                    );
                }
                instance = config;
            }
        }

        /**
         * Swap in a new instance, for when a config source has changed and a
         * fresh {@code Config} has been built from it.
         *
         * <p>Anything already holding the previous instance keeps reading it,
         * and so keeps seeing a consistent snapshot; it moves to the new one
         * when it next calls {@link #get()}. That is deliberate - a caller
         * decides when it is ready for the new configuration rather than
         * having values change underneath it mid-way through a piece of work.
         *
         * @param config
         * the config instance to swap in
         * @throws ConfigException
         * if no instance has been set yet, or {@code config} is null
         */
        public static void replace(Config config) {
            if (config == null) {
                throw new ConfigException("the config instance cannot be null");
            }
            synchronized (LOCK) {
                if (instance == null) {
                    throw new ConfigException("no config instance has been set yet - use `set` first");
                }
                instance.discard();
                instance = config;
            }
        }

        /**
         * Get the instance. Take this once and hold it rather than calling it
         * for every property read - see the note on this class.
         *
         * @return
         * the config instance
         * @throws ConfigException
         * if no instance has been set
         */
        public static Config get() {
            var config = instance; // one volatile read, no lock
            if (config == null) {
                throw new ConfigException("no config instance has been set");
            }
            return config;
        }
    }

    /**
     * A listener for configuration changes.
     */
    public static interface ChangeListener {
        /**
         * Called when the configuration changes.
         *
         * @param event
         * the change event containing details of the change
         */
        public void onChange(ChangeEvent event) throws Exception;

        /**
         * Called when an error occurs while watching the configuration.
         *
         * @param e
         * the exception that occurred
         */
        public void onError(ErrorEvent event) throws Exception;
    }

    /**
     * An event representing a change in a config source.
     *
     * @param source
     * the source of the change
     * @param timestamp
     * the time when the change occurred
     * @param details
     * the details of the change
     */
    public static record ChangeEvent(
        String source,
        Instant timestamp,
        String details
    ){}

    /**
     * An event representing an error that occurred while watching a config
     * source.
     *
     * @param source
     * the source of the error
     * @param timestamp
     * the time when the error occurred
     * @param exception
     * the exception that occurred
     */
    public static record ErrorEvent(
        String source,
        Instant timestamp,
        Exception exception
    ){}

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
