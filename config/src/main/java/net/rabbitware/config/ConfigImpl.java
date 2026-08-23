package net.rabbitware.config;
import java.util.TreeSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigImpl implements Config {
    private static final Logger logger = LoggerFactory.getLogger(ConfigImpl.class);

    private final String name;
    private boolean changeDetectionEnabled;

    private final Map<String, PropertyType> types = new HashMap<>();
    private final Map<String, Value.Boolean> booleanValues = new HashMap<>();
    private final Map<String, Value.Integer> integerValues = new HashMap<>();
    private final Map<String, Value.Long> longValues = new HashMap<>();
    private final Map<String, Value.Double> doubleValues = new HashMap<>();
    private final Map<String, String> stringValues = new HashMap<>();
    private final Map<String, List<Boolean>> booleanListValues = new HashMap<>();
    private final Map<String, List<Integer>> integerListValues = new HashMap<>();
    private final Map<String, List<Long>> longListValues = new HashMap<>();
    private final Map<String, List<Double>> doubleListValues = new HashMap<>();
    private final Map<String, List<String>> stringListValues = new HashMap<>();

    private final Object changeListenersLock = new Object();
    private final Map<String, ChangeListener> changeListeners = new LinkedHashMap<>();
    private final Map<String, Map<String, ChangeListener>> sourceChangeListeners = new LinkedHashMap<>();
    private final Map<String, ChangeEvent> mostRecentChangeEvent = new HashMap<>();
    private final Map<String, ErrorEvent> mostRecentErrorEvent = new HashMap<>();

    private Set<String> propertyNames = Set.of();

    public ConfigImpl(String name, boolean changeDetectionEnabled) {
        this.name = name;
        this.changeDetectionEnabled = changeDetectionEnabled;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Set<String> getPropertyNames() {
        return propertyNames;
    }

    @Override
    public boolean has(String name) {
        return types.containsKey(name);
    }

    @Override
    public PropertyType getType(String name) throws ConfigException {
        PropertyType type = types.get(name);
        if (type == null) {
            throw new PropertyNotFoundException(name);
        }
        return type;
    }

    @Override
    public boolean getBoolean(String name) throws ConfigException {
        Value.Boolean value = booleanValues.get(name);
        if (value != null) { // found it
            return value.b;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.BOOLEAN.name, type);
    }

    @Override
    public int getInt(String name) throws ConfigException {
        Value.Integer value = integerValues.get(name);
        if (value != null) { // found it
            return value.i;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.INT.name, type);
    }

    @Override
    public long getLong(String name) throws ConfigException {
        Value.Long value = longValues.get(name);
        if (value != null) { // found it
            return value.l;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.LONG.name, type);
    }

    @Override
    public double getDouble(String name) throws ConfigException {
        Value.Double value = doubleValues.get(name);
        if (value != null) { // found it
            return value.d;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.DOUBLE.name, type);
    }

    @Override
    public String getString(String name) throws ConfigException {
        String value = stringValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.STRING.name, type);
    }

    @Override
    public List<Boolean> getBooleanList(String name) throws ConfigException {
        List<Boolean> value = booleanListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.BOOLEAN_LIST.name, type);
    }

    @Override
    public List<Integer> getIntList(String name) throws ConfigException {
        List<Integer> value = integerListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.INT_LIST.name, type);
    }

    @Override
    public List<Long> getLongList(String name) throws ConfigException {
        List<Long> value = longListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.LONG_LIST.name, type);
    }

    @Override
    public List<Double> getDoubleList(String name) throws ConfigException {
        List<Double> value = doubleListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.DOUBLE_LIST.name, type);
    }

    @Override
    public List<String> getStringList(String name) throws ConfigException {
        List<String> value = stringListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name).name;
        throw new IncorrectTypeException(name, PropertyType.STRING_LIST.name, type);
    }

    @Override
    public boolean getb(String name) throws ConfigException {
        return getBoolean(name);
    }

    @Override
    public int geti(String name) throws ConfigException {
        return getInt(name);
    }

    @Override
    public long getl(String name) throws ConfigException {
        return getLong(name);
    }

    @Override
    public double getd(String name) throws ConfigException {
        return getDouble(name);
    }

    @Override
    public String gets(String name) throws ConfigException {
        return getString(name);
    }

    @Override
    public List<Boolean> getbl(String name) throws ConfigException {
        return getBooleanList(name);
    }

    @Override
    public List<Integer> getil(String name) throws ConfigException {
        return getIntList(name);
    }

    @Override
    public List<Long> getll(String name) throws ConfigException {
        return getLongList(name);
    }

    @Override
    public List<Double> getdl(String name) throws ConfigException {
        return getDoubleList(name);
    }

    @Override
    public List<String> getsl(String name) throws ConfigException {
        return getStringList(name);
    }

    @Override
    public boolean isChangeDetectionEnabled() {
        return changeDetectionEnabled;
    }

    @Override
    public void addChangeListener(String listenerName, ChangeListener listener) {
        if (!changeDetectionEnabled) {
            throw new ConfigException("change detection is not enabled for this Config object");
        }
        logger.info("adding change listener with name \"{}\"", listenerName);
        if (listenerName == null || listener == null) {
            throw new ConfigException("listener name and listener must not be null");
        }
        if (changeListeners.containsKey(listenerName)) {
            throw new ConfigException("listener with name '" + listenerName + "' already exists");
        }
        synchronized (changeListenersLock) {
            changeListeners.put(listenerName, listener);
        }
        // notify listener of the most recent change and error events, if any
        ChangeEvent changeEvent;
        synchronized (changeListenersLock) { 
            changeEvent = mostRecentChangeEvent.values().stream()
                .sorted((ce1, ce2) -> ce2.timestamp().compareTo(ce1.timestamp())) // reverse chronological order
                .findFirst() // most recent change event
                .orElse(null);
        }
        if (changeEvent != null) {
            notifyChangeListener(listenerName, listener, changeEvent);
        }
        ErrorEvent errorEvent;
        synchronized (changeListenersLock) {
            errorEvent = mostRecentErrorEvent.values().stream()
                .sorted((ee1, ee2) -> ee2.timestamp().compareTo(ee1.timestamp())) // reverse chronological order
                .findFirst() // most recent error event
                .orElse(null);
        }
        if (errorEvent != null) {
            notifyErrorListener(listenerName, listener, errorEvent);
        }
    }

    @Override
    public void removeChangeListener(String listenerName) {
        if (!changeDetectionEnabled) {
            throw new ConfigException("change detection is not enabled for this Config object");
        }
        if (listenerName == null) {
            throw new ConfigException("listener name must not be null");
        }
        if (!changeListeners.containsKey(listenerName)) {
            throw new ConfigException("listener with name '" + listenerName + "' does not exist");
        }
        synchronized (changeListenersLock) {
            changeListeners.remove(listenerName);
        }
    }

    @Override
    public void addChangeListener(String sourceName, String listenerName, ChangeListener listener) {
        if (!changeDetectionEnabled) {
            throw new ConfigException("change detection is not enabled for this Config object");
        }
        logger.info("adding change listener with name \"{}\" for source \"{}\"", listenerName, sourceName);
        if (sourceName == null || listenerName == null || listener == null) {
            throw new ConfigException("source name, listener name, and listener must not be null");
        }
        Map<String, ChangeListener> listeners = sourceChangeListeners.computeIfAbsent(
            sourceName, k -> new LinkedHashMap<>()
        );
        if (listeners.containsKey(listenerName)) {
            throw new ConfigException(
                "listener with name '" + listenerName + "' already exists for source '" + sourceName + "'"
            );
        }
        synchronized (changeListenersLock) {
            listeners.put(listenerName, listener);
        }
        // notify the listener of the most recent change and error events for
        // this source, if any
        ChangeEvent changeEvent;
        synchronized (changeListenersLock) {
            changeEvent = mostRecentChangeEvent.get(sourceName);
        }
        if (changeEvent != null) {
            notifyChangeListener(listenerName, listener, changeEvent);
        }
        ErrorEvent errorEvent;
        synchronized (changeListenersLock) {
            errorEvent = mostRecentErrorEvent.get(sourceName);
        }
        if (errorEvent != null) {
            notifyErrorListener(listenerName, listener, errorEvent);
        }
    }

    @Override
    public void removeChangeListener(String sourceName, String listenerName) {
        if (!changeDetectionEnabled) {
            throw new ConfigException("change detection is not enabled for this Config object");
        }
        if (sourceName == null || listenerName == null) {
            throw new ConfigException("source name and listener name must not be null");
        }
        Map<String, ChangeListener> listeners = sourceChangeListeners.get(sourceName);
        if (listeners == null || !listeners.containsKey(listenerName)) {
            throw new ConfigException(
                "listener with name '" + listenerName + "' does not exist for source '" + sourceName + "'"
            );
        }
        synchronized (changeListenersLock) {
            listeners.remove(listenerName);
        }
    }

    @Override
    public void discard() {
        // stop change detection and release associated resources
        synchronized (changeListenersLock) {
            changeListeners.clear();
            sourceChangeListeners.clear();
            mostRecentChangeEvent.clear();
            mostRecentErrorEvent.clear();
        }
        // ugh - circular dependency with ConfigFactory - refactor?
        ConfigFactory.changeWatcher.discard(this);
        changeDetectionEnabled = false;
    }

    //
    // package-private stuff
    //

    void fireChangeEvent(ChangeEvent event) {
        logger.info("change event received: {}", event);
        // add the change event to the most recent change events map
        synchronized (changeListenersLock) {
            mostRecentChangeEvent.put(event.source(), event);
        }
        // notify all change listeners for this source of the change
        Map<String, ChangeListener> listeners;
        synchronized (changeListenersLock) {
            listeners = Map.copyOf(sourceChangeListeners.computeIfAbsent(
                event.source(), k -> new LinkedHashMap<>()
            ));
        }
        for (Map.Entry<String, ChangeListener> entry : listeners.entrySet()) {
            notifyChangeListener(entry.getKey(), entry.getValue(), event);
        }
        // notify all global change listeners of the change
        synchronized (changeListenersLock) {
            listeners = Map.copyOf(changeListeners);
        }
        for (Map.Entry<String, ChangeListener> entry : listeners.entrySet()) {
            notifyChangeListener(entry.getKey(), entry.getValue(), event);
        }
    }

    void fireErrorEvent(ErrorEvent event) {
        logger.error("error event received - change detection stopped: {}", event, event.exception());
        // add the error event to the most recent error events map
        synchronized (changeListenersLock) {
            mostRecentErrorEvent.put(event.source(), event);
        }
        // notify all change listeners for this source of the error
        Map<String, ChangeListener> listeners;
        synchronized (changeListenersLock) {
            listeners = Map.copyOf(sourceChangeListeners.computeIfAbsent(
                event.source(), k -> new LinkedHashMap<>()
            ));
        }
        for (Map.Entry<String, ChangeListener> entry : listeners.entrySet()) {
            notifyErrorListener(entry.getKey(), entry.getValue(), event);
        }
        // notify all global change listeners of the error
        synchronized (changeListenersLock) {
            listeners = Map.copyOf(changeListeners);
        }
        for (Map.Entry<String, ChangeListener> entry : listeners.entrySet()) {
            notifyErrorListener(entry.getKey(), entry.getValue(), event);
        }
    }

    /**
     * Take the final set of property names. Properties are added one at a
     * time, so this cannot be worked out until the config has been fully
     * built - the factory calls this once it is done adding them.
     * <p>
     * The names are sorted, which is the order callers see when they iterate
     * them, and the set handed out is unmodifiable.
     */
    void freeze() {
        propertyNames = Collections.unmodifiableSortedSet(new TreeSet<>(types.keySet()));
        logger.debug("config frozen with {} properties", propertyNames.size());
    }

    void add(String name, Value value) {
        switch (value) {
            case Value.Boolean booleanValue -> {
                logger.debug("adding boolean property `{}`", name);
                types.put(name, PropertyType.BOOLEAN);
                booleanValues.put(name, booleanValue);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.Integer integerValue -> {
                logger.debug("adding int property `{}`", name);
                types.put(name, PropertyType.INT);
                booleanValues.remove(name);
                integerValues.put(name, integerValue);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.Long longValue -> {
                logger.debug("adding long property `{}`", name);
                types.put(name, PropertyType.LONG);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.put(name, longValue);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.Double doubleValue -> {
                logger.debug("adding double property `{}`", name);
                types.put(name, PropertyType.DOUBLE);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.put(name, doubleValue);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.String stringValue -> {
                logger.debug("adding string property `{}`", name);
                types.put(name, PropertyType.STRING);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.put(name, stringValue.s);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.BooleanList booleanListValue -> {
                logger.debug("adding boolean list property `{}`", name);
                types.put(name, PropertyType.BOOLEAN_LIST);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.put(name, booleanListValue.list.stream().map(b -> b.b).toList());
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.IntegerList integerListValue -> {
                logger.debug("adding int list property `{}`", name);
                types.put(name, PropertyType.INT_LIST);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.put(name, integerListValue.list.stream().map(i -> i.i).toList());
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.LongList longListValue -> {
                logger.debug("adding long list property `{}`", name);
                types.put(name, PropertyType.LONG_LIST);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.put(name, longListValue.list.stream().map(l -> l.l).toList());
                doubleListValues.remove(name);
                stringListValues.remove(name);
            }
            case Value.DoubleList doubleListValue -> {
                logger.debug("adding double list property `{}`", name);
                types.put(name, PropertyType.DOUBLE_LIST);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.put(name, doubleListValue.list.stream().map(d -> d.d).toList() );
                stringListValues.remove(name);
            }
            case Value.StringList stringListValue -> {
                logger.debug("adding string list property `{}`", name);
                types.put(name, PropertyType.STRING_LIST);
                booleanValues.remove(name);
                integerValues.remove(name);
                longValues.remove(name);
                doubleValues.remove(name);
                stringValues.remove(name);
                booleanListValues.remove(name);
                integerListValues.remove(name);
                longListValues.remove(name);
                doubleListValues.remove(name);
                stringListValues.put(name, stringListValue.list.stream().map(s -> s.s).toList());
            }
        }
    }

    void remove(String name) {
        logger.debug("removing property `{}`", name);
        types.remove(name);
        booleanValues.remove(name);
        integerValues.remove(name);
        longValues.remove(name);
        doubleValues.remove(name);
        stringValues.remove(name);
        booleanListValues.remove(name);
        integerListValues.remove(name);
        longListValues.remove(name);
        doubleListValues.remove(name);
        stringListValues.remove(name);
    }


    //
    // private helper methods
    //

    private void notifyChangeListener(String listenerName, ChangeListener listener, ChangeEvent event) {
        logger.debug("notifying change listener `{}` of change: {}", listenerName, event);
        try {
            listener.onChange(event);
        } catch (Exception e) {
            logger.error("problem while notifying change listener `{}` of change", listenerName, e);
        }
    }

    private void notifyErrorListener(String listenerName, ChangeListener listener, ErrorEvent event) {
        logger.debug("notifying change listener `{}` of error: {}", listenerName, event);
        try {
            listener.onError(event);
        } catch (Exception e) {
            logger.error("problem while notifying change listener `{}` of error", listenerName, e);
        }
    }
}
