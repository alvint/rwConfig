package net.rabbitware.config;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigImpl implements Config {
    private final Map<String, String> types = new HashMap<>();
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

    @Override
    public boolean has(String name) {
        return types.containsKey(name);
    }

    @Override
    public String getType(String name) throws ConfigException {
        String type = types.get(name);
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
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.BOOLEAN.name, type);
    }

    @Override
    public int getInt(String name) throws ConfigException {
        Value.Integer value = integerValues.get(name);
        if (value != null) { // found it
            return value.i;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.INT.name, type);
    }

    @Override
    public long getLong(String name) throws ConfigException {
        Value.Long value = longValues.get(name);
        if (value != null) { // found it
            return value.l;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.LONG.name, type);
    }

    @Override
    public double getDouble(String name) throws ConfigException {
        Value.Double value = doubleValues.get(name);
        if (value != null) { // found it
            return value.d;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.DOUBLE.name, type);
    }

    @Override
    public String getString(String name) throws ConfigException {
        String value = stringValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.STRING.name, type);
    }

    @Override
    public List<Boolean> getBooleanList(String name) throws ConfigException {
        List<Boolean> value = booleanListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.BOOLEAN_LIST.name, type);
    }

    @Override
    public List<Integer> getIntList(String name) throws ConfigException {
        List<Integer> value = integerListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.INT_LIST.name, type);
    }

    @Override
    public List<Long> getLongList(String name) throws ConfigException {
        List<Long> value = longListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.LONG_LIST.name, type);
    }

    @Override
    public List<Double> getDoubleList(String name) throws ConfigException {
        List<Double> value = doubleListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name);
        throw new IncorrectTypeException(name, PropertyType.DOUBLE_LIST.name, type);
    }

    @Override
    public List<String> getStringList(String name) throws ConfigException {
        List<String> value = stringListValues.get(name);
        if (value != null) { // found it
            return value;
        }
        // not found or incorrect type
        String type = getType(name);
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


    //
    // package-private stuff
    //

    void add(String name, Value value) {
        switch (value) {
            case Value.Boolean booleanValue -> {
                types.put(name, PropertyType.BOOLEAN.name);
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
                types.put(name, PropertyType.INT.name);
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
                types.put(name, PropertyType.LONG.name);
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
                types.put(name, PropertyType.DOUBLE.name);
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
                types.put(name, PropertyType.STRING.name);
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
                types.put(name, PropertyType.BOOLEAN_LIST.name);
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
                types.put(name, PropertyType.INT_LIST.name);
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
                types.put(name, PropertyType.LONG_LIST.name);
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
                types.put(name, PropertyType.DOUBLE_LIST.name);
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
                types.put(name, PropertyType.STRING_LIST.name);
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
}
