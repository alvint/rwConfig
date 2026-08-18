package net.rabbitware.config.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import net.rabbitware.config.Config;
import net.rabbitware.config.ConfigFactory;

/**
 * What it costs to reach the config through a global holder rather than
 * through a field you already have.
 *
 * <p>The library's selling point is that a read is a couple of nanoseconds, so
 * the way the holder is written matters more here than it would elsewhere: a
 * guard that costs more than the read itself would be the dominant term in
 * every property access an application makes.
 *
 * <p>Three ways of getting to the same {@code Config} are compared - a field, a
 * {@code synchronized} holder, and a {@code volatile} one - single-threaded and
 * again on several threads, since a shared lock only shows its cost when more
 * than one thread wants it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class HolderBenchmark {

    private static final String NAME = "alphaValue";

    /** How the library holds it today. */
    static final class SynchronizedHolder {
        private static final Object LOCK = new Object();
        private static Config instance;

        static void set(Config config) {
            synchronized (LOCK) {
                instance = config;
            }
        }

        static Config get() {
            synchronized (LOCK) {
                if (instance == null) {
                    throw new IllegalStateException("config instance not set");
                }
                return instance;
            }
        }
    }

    /** The same guarantees without a lock on the read path. */
    static final class VolatileHolder {
        private static volatile Config instance;

        static void set(Config config) {
            instance = config;
        }

        static Config get() {
            Config config = instance;
            if (config == null) {
                throw new IllegalStateException("config instance not set");
            }
            return config;
        }
    }

    /** Set-once without a lock, and a deliberate swap for a reload. */
    static final class AtomicHolder {
        private static final AtomicReference<Config> INSTANCE = new AtomicReference<>();

        static void set(Config config) {
            if (!INSTANCE.compareAndSet(null, config)) {
                throw new IllegalStateException("config instance is already set");
            }
        }

        static void replace(Config config) {
            INSTANCE.set(config);
        }

        static Config get() {
            Config config = INSTANCE.get();
            if (config == null) {
                throw new IllegalStateException("config instance not set");
            }
            return config;
        }
    }

    private Config config;
    private Path file;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = sys", "rwc.sys.type = systemProperties"));
        for (int i = 0; i < 100; i++) {
            lines.add("int prop" + i + " = " + i);
        }
        lines.add("int " + NAME + " = 50");
        file = Files.createTempFile("holder", null);
        Files.write(file, lines);
        config = ConfigFactory.create(new String[] {
            ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file});
        SynchronizedHolder.set(config);
        VolatileHolder.set(config);
        AtomicHolder.set(config);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        Files.deleteIfExists(file);
    }


    //
    // one thread
    //

    /** The floor: the caller already has the config. */
    @Benchmark
    public int fieldRead() {
        return config.getInt(NAME);
    }

    @Benchmark
    public int synchronizedHolderRead() {
        return SynchronizedHolder.get().getInt(NAME);
    }

    @Benchmark
    public int volatileHolderRead() {
        return VolatileHolder.get().getInt(NAME);
    }


    @Benchmark
    public int atomicHolderRead() {
        return AtomicHolder.get().getInt(NAME);
    }


    //
    // several threads, where a shared lock actually bites
    //

    @Benchmark
    @Threads(8)
    public int fieldReadContended() {
        return config.getInt(NAME);
    }

    @Benchmark
    @Threads(8)
    public int synchronizedHolderReadContended() {
        return SynchronizedHolder.get().getInt(NAME);
    }

    @Benchmark
    @Threads(8)
    public int volatileHolderReadContended() {
        return VolatileHolder.get().getInt(NAME);
    }

    @Benchmark
    @Threads(8)
    public int atomicHolderReadContended() {
        return AtomicHolder.get().getInt(NAME);
    }
}
