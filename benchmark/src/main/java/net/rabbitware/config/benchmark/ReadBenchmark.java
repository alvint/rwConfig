package net.rabbitware.config.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

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
import net.rabbitware.config.ConfigFactory;

/**
 * Benchmarks for reading values, which is the claim in the README: that
 * {@code getInt} and its primitive siblings are faster than a {@code HashMap}
 * and close enough to a field read that caching a value is not worth it.
 *
 * <p>Each read is compared against three reference points:
 * <ul>
 * <li>a field, which is the floor - nothing can be faster</li>
 * <li>a {@code HashMap<String, Integer>}, the obvious thing to reach for, and
 *     the one the claim is made against</li>
 * <li>{@code Properties.getProperty} followed by a parse, which is what an
 *     application does when it has no config library at all</li>
 * </ul>
 *
 * <p>The config holds 100 properties so the maps are not trivially small.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ReadBenchmark {

    /** The property every benchmark reads, sitting in the middle of the set. */
    private static final String NAME = "prop50";

    private Config config;
    private Map<String, Integer> hashMap;
    private Properties properties;
    private int field;
    private Path configFile;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = sys",
            "rwc.sys.type = systemProperties"
        ));
        for (int i = 0; i < 100; i++) {
            lines.add("int prop" + i + " = " + i);
        }
        lines.add("string myString = a string value");
        lines.add("long myLong = 5000000000");
        lines.add("double myDouble = 1.5");
        lines.add("boolean myBoolean = true");
        lines.add("intList myList = 1, 2, 3");

        configFile = Files.createTempFile("rwconfig-benchmark", null);
        Files.write(configFile, lines);
        config = ConfigFactory.create(new String[] {
            ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + configFile
        });

        hashMap = new HashMap<>();
        properties = new Properties();
        for (int i = 0; i < 100; i++) {
            hashMap.put("prop" + i, i);
            properties.setProperty("prop" + i, String.valueOf(i));
        }
        field = 50;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        Files.deleteIfExists(configFile);
    }


    //
    // the reference points
    //

    /** The floor. Nothing can beat reading a field. */
    @Benchmark
    public int aField() {
        return field;
    }

    /** The comparison the README makes: a plain map of name to boxed value. */
    @Benchmark
    public int hashMapGet() {
        return hashMap.get(NAME);
    }

    /** What an application does with no config library: look up, then parse. */
    @Benchmark
    public int propertiesGetAndParse() {
        return Integer.parseInt(properties.getProperty(NAME));
    }


    //
    // the library
    //

    @Benchmark
    public int configGetInt() {
        return config.getInt(NAME);
    }

    @Benchmark
    public long configGetLong() {
        return config.getLong("myLong");
    }

    @Benchmark
    public double configGetDouble() {
        return config.getDouble("myDouble");
    }

    @Benchmark
    public boolean configGetBoolean() {
        return config.getBoolean("myBoolean");
    }

    @Benchmark
    public String configGetString() {
        return config.getString("myString");
    }

    @Benchmark
    public List<Integer> configGetIntList() {
        return config.getIntList("myList");
    }


    //
    // is caching a value worth it?
    //

    /**
     * Reading the same property ten times, as a loop in application code
     * might. Compare with {@link #tenCachedReads()} - if the two are close,
     * copying the value into a local first buys nothing.
     */
    @Benchmark
    public int tenConfigReads() {
        int total = 0;
        for (int i = 0; i < 10; i++) {
            total += config.getInt(NAME);
        }
        return total;
    }

    /** The same ten reads, hoisted out of the loop by hand. */
    @Benchmark
    public int tenCachedReads() {
        int cached = config.getInt(NAME);
        int total = 0;
        for (int i = 0; i < 10; i++) {
            total += cached;
        }
        return total;
    }
}
