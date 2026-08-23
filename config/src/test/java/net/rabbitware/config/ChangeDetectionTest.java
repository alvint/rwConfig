package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.rabbitware.config.Config.ConfigException;

/**
 * Tests for noticing that a config source has changed.
 *
 * <p>These use a `properties` source pointed at a file, because that is the
 * one source type that both supports change detection and needs no plugin jar.
 * The polling interval is turned down so the tests do not wait on the five
 * second default, and every wait is a deadline rather than a fixed sleep -
 * detection latency depends on the platform's file watcher, which on macOS is
 * a polling implementation of its own.
 */
class ChangeDetectionTest {

    @TempDir
    private Path tempDir;

    private final List<Config> created = new ArrayList<>();

    @AfterEach
    void discardEverything() {
        // a config that is never discarded keeps its polling thread and is held
        // by the factory forever, which would leak across the whole test run
        created.forEach(config -> {
            try {
                config.discard();
            } catch (Exception e) {
                // already discarded by the test itself
            }
        });
    }

    /** A config over a properties file, with change detection on and polling fast. */
    private Config config(Path propertiesFile, String... declarations) throws IOException {
        Path file = tempDir.resolve("rwconfig-" + created.size());
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = f",
            "rwc.f.type = properties",
            "rwc.f.location = file:" + propertiesFile,
            "rwc.changeDetectionPollingInterval = 100"
        ));
        lines.addAll(Arrays.asList(declarations));
        Files.write(file, lines);
        Config config = ConfigFactory.create(
            true, new String[] { ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file });
        created.add(config);
        return config;
    }

    private Path propertiesFile(String contents) throws IOException {
        Path file = tempDir.resolve("app-" + created.size() + ".properties");
        Files.writeString(file, contents);
        return file;
    }

    /** Wait until the count reaches at least `wanted`, or give up. Returns what it saw. */
    private int await(AtomicInteger counter, int wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && counter.get() < wanted) {
            Thread.sleep(50);
        }
        return counter.get();
    }

    /** Give any pending event time to arrive, for the cases that expect none. */
    private void settle() throws InterruptedException {
        Thread.sleep(1_500);
    }

    @Nested
    @DisplayName("noticing a change")
    class Noticing {

        @Test
        @DisplayName("a changed file fires a change event naming its source")
        void aChangedFileFiresAnEvent() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Config config = config(properties, "int port = 1");
            AtomicInteger changes = new AtomicInteger();
            List<Config.ChangeEvent> events = new CopyOnWriteArrayList<>();
            config.addChangeListener("l", new Config.ChangeListener() {
                @Override public void onChange(Config.ChangeEvent event) {
                    events.add(event);
                    changes.incrementAndGet();
                }
                @Override public void onError(Config.ErrorEvent event) { }
            });

            Files.writeString(properties, "port=9090\n");

            assertTrue(await(changes, 1) >= 1, "the change should have been noticed");
            assertEquals("f", events.get(0).source(), "the event names the source that changed");
            assertNotNull(events.get(0).timestamp());
        }

        @Test
        @DisplayName("the values already read do not change underneath the application")
        void theConfigItselfIsStillASnapshot() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Config config = config(properties, "int port = 1");
            AtomicInteger changes = new AtomicInteger();
            config.addChangeListener("l", listener(changes));

            Files.writeString(properties, "port=9090\n");
            await(changes, 1);

            assertEquals(8080, config.getInt("port"),
                "a change event is a notification - the Config is not rebuilt behind your back");
        }

        @Test
        @DisplayName("a listener cannot be registered unless change detection was asked for")
        void offByDefault() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Path file = tempDir.resolve("rwconfig-off");
            Files.write(file, List.of(
                "rwc.sources = f",
                "rwc.f.type = properties",
                "rwc.f.location = file:" + properties,
                "int port = 1"));
            Config config = ConfigFactory.create(          // no `true` - detection off
                new String[] { ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file });
            created.add(config);

            // Refusing the listener is better than accepting one that could never
            // fire: the mistake is reported where it is made, not by silence.
            ConfigException e = assertThrows(ConfigException.class,
                () -> config.addChangeListener("l", listener(new AtomicInteger())));
            assertTrue(e.getMessage().contains("change detection is not enabled"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        @DisplayName("a listener for one source hears only that source")
        void perSourceListeners() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Config config = config(properties, "int port = 1");
            AtomicInteger mine = new AtomicInteger(), other = new AtomicInteger();
            config.addChangeListener("f", "l", listener(mine));
            config.addChangeListener("nosuchsource", "l", listener(other));

            Files.writeString(properties, "port=9090\n");

            assertTrue(await(mine, 1) >= 1, "the listener on `f` should hear it");
            assertEquals(0, other.get(), "a listener on another source should not");
        }

        @Test
        @DisplayName("a removed listener stops hearing")
        void removedListenersAreSilent() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Config config = config(properties, "int port = 1");
            AtomicInteger changes = new AtomicInteger();
            config.addChangeListener("l", listener(changes));

            Files.writeString(properties, "port=9090\n");
            await(changes, 1);
            int before = changes.get();
            config.removeChangeListener("l");

            Files.writeString(properties, "port=7070\n");
            settle();

            assertEquals(before, changes.get(), "no further events after removal");
        }
    }

    @Nested
    @DisplayName("discarding a config")
    class Discarding {

        @Test
        @DisplayName("stops the events")
        void discardStopsEvents() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Config config = config(properties, "int port = 1");
            AtomicInteger changes = new AtomicInteger();
            config.addChangeListener("l", listener(changes));

            Files.writeString(properties, "port=9090\n");
            await(changes, 1);
            config.discard();
            int afterDiscard = changes.get();

            Files.writeString(properties, "port=7070\n");
            settle();

            assertEquals(afterDiscard, changes.get(), "a discarded config stops watching");
        }

        @Test
        @DisplayName("can be done twice without complaint")
        void discardIsIdempotent() throws Exception {
            Config config = config(propertiesFile("port=8080\n"), "int port = 1");
            config.discard();
            config.discard();
        }

        @Test
        @DisplayName("leaves the values readable - it stops watching, it does not empty the config")
        void discardKeepsTheValues() throws Exception {
            Config config = config(propertiesFile("port=8080\n"), "int port = 1");
            config.discard();
            assertEquals(8080, config.getInt("port"));
        }
    }

    @Nested
    @DisplayName("the global instance holder")
    class InstanceHolder {

        @AfterEach
        void clearTheHolder() throws Exception {
            // the holder is static, so it has to be emptied between tests
            java.lang.reflect.Field field = Config.Instance.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, null);
        }

        @Test
        @DisplayName("`replace` discards the instance it swaps out")
        void replaceDiscardsTheOldInstance() throws Exception {
            Path properties = propertiesFile("port=8080\n");
            Config first = config(properties, "int port = 1");
            AtomicInteger firstHeard = new AtomicInteger();
            first.addChangeListener("l", listener(firstHeard));
            Config.Instance.set(first);

            Config second = config(properties, "int port = 1");
            AtomicInteger secondHeard = new AtomicInteger();
            second.addChangeListener("l", listener(secondHeard));
            Config.Instance.replace(second);

            Files.writeString(properties, "port=9090\n");

            assertTrue(await(secondHeard, 1) >= 1, "the new instance is watching");
            assertEquals(0, firstHeard.get(),
                "the replaced instance was discarded, so it is no longer watching");
        }

        @Test
        @DisplayName("`replace` still swaps what `get` returns")
        void replaceSwapsTheInstance() throws Exception {
            Config first = config(propertiesFile("port=8080\n"), "int port = 1");
            Config second = config(propertiesFile("port=9090\n"), "int port = 1");
            Config.Instance.set(first);
            Config.Instance.replace(second);
            assertEquals(9090, Config.Instance.get().getInt("port"));
        }
    }

    @Nested
    @DisplayName("the polling interval setting")
    class PollingInterval {

        @Test
        @DisplayName("a value that is not a number is rejected")
        void notANumber() throws Exception {
            assertTrue(rejected("rwc.changeDetectionPollingInterval = soon")
                .getMessage().contains("changeDetectionPollingInterval"));
        }

        @Test
        @DisplayName("zero and negative values are rejected")
        void mustBePositive() throws Exception {
            assertTrue(rejected("rwc.changeDetectionPollingInterval = 0")
                .getMessage().contains("must be positive"));
            assertTrue(rejected("rwc.changeDetectionPollingInterval = -1")
                .getMessage().contains("must be positive"));
        }

        private ConfigException rejected(String intervalLine) throws IOException {
            Path properties = propertiesFile("port=8080\n");
            Path file = tempDir.resolve("rwconfig-bad-" + System.nanoTime());
            Files.write(file, List.of(
                "rwc.sources = f",
                "rwc.f.type = properties",
                "rwc.f.location = file:" + properties,
                intervalLine,
                "int port = 1"));
            return assertThrows(ConfigException.class, () -> ConfigFactory.create(
                true, new String[] { ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file }));
        }
    }

    /** A listener that only counts. */
    private static Config.ChangeListener listener(AtomicInteger counter) {
        return new Config.ChangeListener() {
            @Override public void onChange(Config.ChangeEvent event) { counter.incrementAndGet(); }
            @Override public void onError(Config.ErrorEvent event) { }
        };
    }
}
