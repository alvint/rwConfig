package net.rabbitware.config.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import net.rabbitware.config.Config;
import net.rabbitware.config.ConfigFactory;

/**
 * Benchmarks for building the config, which is where all the work happens -
 * reading the file, loading every source, and parsing and validating every
 * value. This runs once per application start.
 *
 * <p>Reported per whole config rather than per property, so divide by the
 * property count to compare across sizes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class StartupBenchmark {

    @Param({"10", "100", "1000"})
    private int propertyCount;

    private String[] plainArgs;
    private String[] escapedArgs;
    private Path plainFile;
    private Path escapedFile;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        // both files are written once, up front - writing one inside a
        // benchmark would measure the filesystem rather than the library
        plainFile = writeConfig("rwconfig-plain", "value");
        escapedFile = writeConfig("rwconfig-escaped", "a\\tvalue\\, with escapes");
        plainArgs = pathArgs(plainFile);
        escapedArgs = pathArgs(escapedFile);
    }

    private Path writeConfig(String prefix, String valuePattern) throws Exception {
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = sys",
            "rwc.sys.type = systemProperties"
        ));
        for (int i = 0; i < propertyCount; i++) {
            lines.add("string prop" + i + " = " + valuePattern + i);
        }
        Path file = Files.createTempFile(prefix, null);
        Files.write(file, lines);
        return file;
    }

    private static String[] pathArgs(Path file) {
        return new String[] {ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file};
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        Files.deleteIfExists(plainFile);
        Files.deleteIfExists(escapedFile);
    }

    /** Reading the file, loading the sources, parsing and validating. */
    @Benchmark
    public Config createConfig() {
        return ConfigFactory.create(plainArgs);
    }

    /**
     * The same, with values carrying escape sequences. Unescaping is the most
     * expensive part of parsing a value, so the gap between this and
     * {@link #createConfig()} is roughly what escapes cost.
     */
    @Benchmark
    public Config createConfigWithEscapes() {
        return ConfigFactory.create(escapedArgs);
    }
}
