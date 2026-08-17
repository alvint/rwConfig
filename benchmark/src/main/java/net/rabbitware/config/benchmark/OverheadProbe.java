package net.rabbitware.config.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

import net.rabbitware.config.Config;
import net.rabbitware.config.ConfigFactory;

/**
 * Diagnostic: does merely initialising the other config libraries slow down an
 * unrelated rwConfig read? The config is built identically in both cases; the
 * only difference is whether several megabytes of other libraries are loaded
 * into the same JVM afterwards.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class OverheadProbe {

    /** Owner needs an interface to proxy. */
    public interface OwnerProbeConfig extends org.aeonbits.owner.Config {
        @Key("prop50")
        int prop50();
    }

    @Param({"none", "all"})
    private String alsoLoad;

    private Config config;
    private Path file;
    private Object[] kept;
    private Map<String, Integer> plainMap;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = sys", "rwc.sys.type = systemProperties"));
        for (int i = 0; i < 100; i++) {
            lines.add("int prop" + i + " = " + i);
        }
        file = Files.createTempFile("overhead", null);
        Files.write(file, lines);
        config = ConfigFactory.create(new String[] {
            ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file});
        plainMap = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            plainMap.put("prop" + i, i);
        }

        if (!alsoLoad.equals("none")) {
            Properties props = new Properties();
            for (int i = 0; i < 100; i++) {
                props.setProperty("prop" + i, String.valueOf(i));
            }
            Map<String, String> map = new HashMap<>();
            props.forEach((k, v) -> map.put((String) k, (String) v));

            var env = new org.springframework.core.env.StandardEnvironment();
            env.getPropertySources().addFirst(
                new org.springframework.core.env.PropertiesPropertySource("b", props));
            var smallrye = new io.smallrye.config.SmallRyeConfigBuilder()
                .withSources(new io.smallrye.config.PropertiesConfigSource(map, "b", 100)).build();
            com.netflix.config.ConfigurationManager.loadProperties(props);
            var archaius = com.netflix.config.DynamicPropertyFactory.getInstance()
                .getIntProperty("prop50", -1);
            io.avaje.config.Config.setProperty("prop50", "50");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // the three the first version of this probe left out
            Path pf = Files.createTempFile("overhead", ".properties");
            List<String> plines = new ArrayList<>();
            List<String> hlines = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                plines.add("prop" + i + "=" + i);
                hlines.add("prop" + i + " = " + i);
            }
            Files.write(pf, plines);
            Path hf = Files.createTempFile("overhead", ".conf");
            Files.write(hf, hlines);
            var typesafe = com.typesafe.config.ConfigFactory.parseFile(hf.toFile()).resolve();
            var commons = new org.apache.commons.configuration2.builder.fluent.Configurations()
                .properties(pf.toFile());
            var owner = org.aeonbits.owner.ConfigFactory.create(OwnerProbeConfig.class, props);
            kept = new Object[] {env, smallrye, archaius, mapper, props, map,
                                 typesafe, commons, owner};
            Files.deleteIfExists(pf);
            Files.deleteIfExists(hf);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        Files.deleteIfExists(file);
        // `kept` exists so the libraries loaded above stay strongly reachable
        // for the whole trial rather than being collected part way through it.
        // Reading it here is what makes that hold - and what stops the field
        // looking unused, which is exactly how it would get deleted by someone
        // tidying up later.
        if (kept != null && kept.length == 0) {
            throw new IllegalStateException("nothing was kept alive");
        }
    }

    @Benchmark
    public int rwConfigRead() {
        return config.getInt("prop50");
    }

    /**
     * A plain HashMap lookup, which has nothing to do with rwConfig but shares
     * the same JDK call sites (HashMap.get, String.equals, String.hashCode).
     * If this slows down too, the cause is those shared sites rather than
     * anything in the library.
     */
    @Benchmark
    public int plainHashMapRead() {
        return plainMap.get("prop50");
    }
}
