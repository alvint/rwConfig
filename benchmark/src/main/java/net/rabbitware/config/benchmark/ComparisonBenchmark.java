package net.rabbitware.config.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import net.rabbitware.config.Config;

/**
 * Reads the same configuration through several config libraries, so the cost
 * of a read can be compared across them.
 *
 * <p><b>Read these numbers carefully.</b> The libraries are not doing the same
 * amount of work, and almost all of the spread is a design choice rather than
 * one implementation being better written than another:
 *
 * <ul>
 * <li><b>rwConfig</b> parses and validates every value against a declared type
 *     at load, so a read is a map lookup of an already-typed value.</li>
 * <li><b>Typesafe Config</b> parses at load, but resolves a dotted path on
 *     every read.</li>
 * <li><b>Commons Configuration</b>, <b>Spring</b>, <b>SmallRye Config</b> and
 *     <b>avaje-config</b> convert on every read.</li>
 * <li><b>Owner</b> reads through a dynamic proxy and converts on each call.</li>
 * <li><b>Archaius</b> is read through a handle obtained once and held, which is
 *     the idiom its documentation recommends. Reading the handle is close to a
 *     field access. Both the legacy 1.x line and the current 2.x line are
 *     measured, in both idioms.</li>
 * <li><b>Jackson</b> stands in for binding configuration into a record once and
 *     reading fields afterwards, so its "read" is a field access. The
 *     interesting number for that approach is the bind, under loading.</li>
 * <li><b>Properties</b> is the no-library baseline: look up a string, parse
 *     it.</li>
 * </ul>
 *
 * <p><b>Each library has its own {@code @State} class on purpose.</b> JMH only
 * initialises the states a benchmark declares, and forks per benchmark, so no
 * library's setup runs in the JVM that measures another. An earlier version of
 * this class built everything in one shared state, which inflated every
 * map-lookup read by about 1.3 ns: loading nine config libraries pushes shared
 * JDK call sites such as {@code HashMap.get} from monomorphic to megamorphic
 * and the JIT stops inlining them. {@link OverheadProbe} measures that effect
 * directly. If you add a library here, give it its own state.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ComparisonBenchmark {

    // Deliberately free of digits. Typesafe Config parses a dotted path on
    // every read and is roughly 10x slower when a path element contains a
    // digit, so benchmarking `prop50` would say more about that quirk than
    // about the cost of a read. The quirk is measured separately below.
    static final String INT_NAME = "alphaValue";
    static final String STRING_NAME = "betaValue";
    static final String DIGIT_NAME = "prop50";
    static final int PROPERTY_COUNT = 100;

    /** The same configuration every library is given. */
    static Properties properties() {
        Properties properties = new Properties();
        for (int i = 0; i < PROPERTY_COUNT; i++) {
            properties.setProperty("prop" + i, String.valueOf(i));
        }
        properties.setProperty(INT_NAME, "50");
        properties.setProperty(STRING_NAME, "a string value");
        return properties;
    }

    static Map<String, String> asMap() {
        Map<String, String> map = new HashMap<>();
        properties().forEach((k, v) -> map.put((String) k, (String) v));
        return map;
    }

    static Path writeFile(String prefix, String suffix, List<String> lines) throws Exception {
        Path file = Files.createTempFile(prefix, suffix);
        Files.write(file, lines);
        return file;
    }

    static Path propertiesFile() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < PROPERTY_COUNT; i++) {
            lines.add("prop" + i + "=" + i);
        }
        lines.add(INT_NAME + "=50");
        lines.add(STRING_NAME + "=a string value");
        return writeFile("comparison", ".properties", lines);
    }


    //
    // one state per library, so no library's setup pollutes another's
    // measurement
    //

    @State(Scope.Benchmark)
    public static class RwConfigState {
        Config config;
        Path file;
        String[] args;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            List<String> lines = new ArrayList<>(List.of(
                "rwc.sources = sys", "rwc.sys.type = systemProperties"));
            for (int i = 0; i < PROPERTY_COUNT; i++) {
                lines.add("int prop" + i + " = " + i);
            }
            lines.add("int " + INT_NAME + " = 50");
            lines.add("string " + STRING_NAME + " = a string value");
            file = writeFile("comparison-rwconfig", null, lines);
            args = new String[] {
                net.rabbitware.config.ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file};
            config = net.rabbitware.config.ConfigFactory.create(args);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            Files.deleteIfExists(file);
        }
    }

    @State(Scope.Benchmark)
    public static class TypesafeState {
        com.typesafe.config.Config config;
        Path file;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < PROPERTY_COUNT; i++) {
                lines.add("prop" + i + " = " + i);
            }
            lines.add(INT_NAME + " = 50");
            lines.add(STRING_NAME + " = \"a string value\"");
            file = writeFile("comparison", ".conf", lines);
            config = com.typesafe.config.ConfigFactory.parseFile(file.toFile()).resolve();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            Files.deleteIfExists(file);
        }
    }

    @State(Scope.Benchmark)
    public static class CommonsState {
        PropertiesConfiguration config;
        Path file;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            file = propertiesFile();
            config = new Configurations().properties(file.toFile());
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            Files.deleteIfExists(file);
        }
    }

    @State(Scope.Benchmark)
    public static class PropertiesState {
        Properties properties;
        Path file;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            file = propertiesFile();
            properties = new Properties();
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            Files.deleteIfExists(file);
        }
    }

    /** The interface Owner reads through. */
    public interface OwnerConfig extends org.aeonbits.owner.Config {
        @Key("alphaValue")
        int alphaValue();

        @Key("betaValue")
        String betaValue();
    }

    @State(Scope.Benchmark)
    public static class OwnerState {
        OwnerConfig config;

        @Setup(Level.Trial)
        public void setUp() {
            config = org.aeonbits.owner.ConfigFactory.create(OwnerConfig.class, properties());
        }
    }

    @State(Scope.Benchmark)
    public static class SpringState {
        org.springframework.core.env.StandardEnvironment environment;

        @Setup(Level.Trial)
        public void setUp() {
            environment = new org.springframework.core.env.StandardEnvironment();
            environment.getPropertySources().addFirst(
                new org.springframework.core.env.PropertiesPropertySource(
                    "benchmark", properties()));
        }
    }

    @State(Scope.Benchmark)
    public static class SmallRyeState {
        io.smallrye.config.SmallRyeConfig config;
        Map<String, String> map;

        @Setup(Level.Trial)
        public void setUp() {
            map = asMap();
            config = build(map);
        }

        static io.smallrye.config.SmallRyeConfig build(Map<String, String> map) {
            return new io.smallrye.config.SmallRyeConfigBuilder()
                .withSources(new io.smallrye.config.PropertiesConfigSource(map, "benchmark", 100))
                .build();
        }
    }

    @State(Scope.Benchmark)
    public static class Archaius1State {
        com.netflix.config.DynamicIntProperty intProperty;
        com.netflix.config.DynamicStringProperty stringProperty;

        @Setup(Level.Trial)
        public void setUp() {
            com.netflix.config.ConfigurationManager.loadProperties(properties());
            var factory = com.netflix.config.DynamicPropertyFactory.getInstance();
            intProperty = factory.getIntProperty(INT_NAME, -1);
            stringProperty = factory.getStringProperty(STRING_NAME, "");
        }
    }

    /**
     * Archaius 2, the current line. It offers the same two idioms as 1.x: a
     * {@code Property} handle obtained once and held, or a direct lookup on
     * the {@code Config}.
     */
    @State(Scope.Benchmark)
    public static class Archaius2State {
        com.netflix.archaius.api.Config config;
        com.netflix.archaius.api.Property<Integer> intProperty;
        com.netflix.archaius.api.Property<String> stringProperty;

        @Setup(Level.Trial)
        public void setUp() {
            config = com.netflix.archaius.config.MapConfig.builder().putAll(asMap()).build();
            var factory = com.netflix.archaius.DefaultPropertyFactory.from(config);
            intProperty = factory.get(INT_NAME, Integer.class);
            stringProperty = factory.get(STRING_NAME, String.class);
        }
    }

    @State(Scope.Benchmark)
    public static class AvajeState {
        @Setup(Level.Trial)
        public void setUp() {
            io.avaje.config.Config.setProperty(INT_NAME, "50");
            io.avaje.config.Config.setProperty(STRING_NAME, "a string value");
        }
    }

    /** What Jackson binds the configuration into. */
    public record Settings(int alphaValue, String betaValue) {}

    @State(Scope.Benchmark)
    public static class JacksonState {
        com.fasterxml.jackson.databind.ObjectMapper mapper;
        Map<String, String> map;
        Settings bound;

        @Setup(Level.Trial)
        public void setUp() {
            mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // the config holds far more than this record binds, which is the
            // normal case when binding a subset of the configuration
            mapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
            map = asMap();
            bound = mapper.convertValue(map, Settings.class);
        }
    }


    //
    // reading an int
    //

    @Benchmark
    public int rwConfigInt(RwConfigState state) {
        return state.config.getInt(INT_NAME);
    }

    @Benchmark
    public int typesafeInt(TypesafeState state) {
        return state.config.getInt(INT_NAME);
    }

    @Benchmark
    public int commonsInt(CommonsState state) {
        return state.config.getInt(INT_NAME);
    }

    @Benchmark
    public int ownerInt(OwnerState state) {
        return state.config.alphaValue();
    }

    @Benchmark
    public int propertiesInt(PropertiesState state) {
        return Integer.parseInt(state.properties.getProperty(INT_NAME));
    }

    @Benchmark
    public int springInt(SpringState state) {
        return state.environment.getProperty(INT_NAME, Integer.class);
    }

    @Benchmark
    public int smallryeInt(SmallRyeState state) {
        return state.config.getValue(INT_NAME, Integer.class);
    }

    @Benchmark
    public int archaius1Int(Archaius1State state) {
        return state.intProperty.get();
    }

    @Benchmark
    public int avajeInt(AvajeState state) {
        return io.avaje.config.Config.getInt(INT_NAME);
    }

    @Benchmark
    public int archaius2Int(Archaius2State state) {
        return state.intProperty.get();
    }

    /** Archaius 2 asked by name on each read, rather than through a handle. */
    @Benchmark
    public int archaius2IntByName(Archaius2State state) {
        return state.config.getInteger(INT_NAME);
    }

    /** Reading a field of an already-bound record - effectively the floor. */
    @Benchmark
    public int jacksonBoundInt(JacksonState state) {
        return state.bound.alphaValue();
    }

    /**
     * Archaius asked for a property <em>by name</em> on each read, rather than
     * through a handle obtained once. This is what it costs when the name is
     * not known ahead of time - the same question the other libraries answer
     * on every call.
     */
    @Benchmark
    public int archaius1IntByName(Archaius1State state) {
        return com.netflix.config.DynamicPropertyFactory.getInstance()
            .getIntProperty(INT_NAME, -1).get();
    }


    //
    // reading a string
    //

    @Benchmark
    public String rwConfigString(RwConfigState state) {
        return state.config.getString(STRING_NAME);
    }

    @Benchmark
    public String typesafeString(TypesafeState state) {
        return state.config.getString(STRING_NAME);
    }

    @Benchmark
    public String commonsString(CommonsState state) {
        return state.config.getString(STRING_NAME);
    }

    @Benchmark
    public String ownerString(OwnerState state) {
        return state.config.betaValue();
    }

    @Benchmark
    public String propertiesString(PropertiesState state) {
        return state.properties.getProperty(STRING_NAME);
    }

    @Benchmark
    public String springString(SpringState state) {
        return state.environment.getProperty(STRING_NAME);
    }

    @Benchmark
    public String smallryeString(SmallRyeState state) {
        return state.config.getValue(STRING_NAME, String.class);
    }

    @Benchmark
    public String archaius1String(Archaius1State state) {
        return state.stringProperty.get();
    }

    @Benchmark
    public String archaius2String(Archaius2State state) {
        return state.stringProperty.get();
    }

    @Benchmark
    public String avajeString(AvajeState state) {
        return io.avaje.config.Config.get(STRING_NAME);
    }

    @Benchmark
    public String jacksonBoundString(JacksonState state) {
        return state.bound.betaValue();
    }


    //
    // the cost of a digit in the key name
    //

    @Benchmark
    public int rwConfigIntDigitKey(RwConfigState state) {
        return state.config.getInt(DIGIT_NAME);
    }

    @Benchmark
    public int typesafeIntDigitKey(TypesafeState state) {
        return state.config.getInt(DIGIT_NAME);
    }


    //
    // loading the whole configuration
    //

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Config rwConfigLoad(RwConfigState state) {
        return net.rabbitware.config.ConfigFactory.create(state.args);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public com.typesafe.config.Config typesafeLoad(TypesafeState state) {
        return com.typesafe.config.ConfigFactory.parseFile(state.file.toFile()).resolve();
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public PropertiesConfiguration commonsLoad(CommonsState state) throws Exception {
        return new Configurations().properties(state.file.toFile());
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Properties propertiesLoad(PropertiesState state) throws Exception {
        Properties loaded = new Properties();
        try (var in = Files.newInputStream(state.file)) {
            loaded.load(in);
        }
        return loaded;
    }

    /** Building from an already-parsed map, with no file reading or parsing. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public io.smallrye.config.SmallRyeConfig smallryeLoad(SmallRyeState state) {
        return SmallRyeState.build(state.map);
    }

    /** Binding an already-parsed map into a record, with no file parsing. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Settings jacksonBind(JacksonState state) {
        return state.mapper.convertValue(state.map, Settings.class);
    }
}
