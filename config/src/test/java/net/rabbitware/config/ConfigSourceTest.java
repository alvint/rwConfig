package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.rabbitware.config.Config.ConfigException;

/**
 * Tests for loading property values from config sources - which sources are
 * supported, how they are prioritized, and what happens when one of them is
 * declared incorrectly.
 */
class ConfigSourceTest {

    @TempDir
    private Path tempDir;

    private final List<String> systemPropertiesSet = new ArrayList<>();

    @AfterEach
    void clearSystemProperties() {
        systemPropertiesSet.forEach(System::clearProperty);
    }

    /** Set a system property and remember to clear it after the test. */
    private void setSystemProperty(String name, String value) {
        systemPropertiesSet.add(name);
        System.setProperty(name, value);
    }

    /** Build a config file out of the given lines and load it with no arguments. */
    private Config config(String... lines) throws IOException {
        return config(new String[0], lines);
    }

    /** Build a config file out of the given lines and load it with the given arguments. */
    private Config config(String[] args, String... lines) throws IOException {
        Path file = tempDir.resolve("rwconfig");
        Files.write(file, Arrays.asList(lines));
        String[] allArgs = new String[args.length + 1];
        allArgs[0] = ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        return ConfigFactory.create(allArgs);
    }

    private ConfigException rejected(String... lines) {
        return assertThrows(ConfigException.class, () -> config(lines));
    }

    /** Write a properties file into the temp directory and return its location. */
    private String propertiesFile(String name, String... lines) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, Arrays.asList(lines));
        return "file:" + file;
    }


    @Nested
    @DisplayName("the built-in source types")
    class SourceTypes {

        @Test
        void systemProperties() throws IOException {
            setSystemProperty("myTestProperty", "fromSystemProperties");
            Config config = config(
                "rwc.sources = sys",
                "rwc.sys.type = systemProperties",
                "rwc.sys.ignoreUnknownProperties = true",
                "myTestProperty"
            );
            assertEquals("fromSystemProperties", config.gets("myTestProperty"));
        }

        @Test
        void commandLineArguments() throws IOException {
            Config config = config(
                new String[] {"myProp=fromTheCommandLine"},
                "rwc.sources = args",
                "rwc.args.type = commandLineArguments",
                "rwc.args.ignoreUnknownProperties = true",
                "myProp"
            );
            assertEquals("fromTheCommandLine", config.gets("myProp"));
        }

        @Test
        @DisplayName("the environment variables source reads a variable that is always set")
        void environmentVariables() throws IOException {
            // PATH is set in every environment this is likely to run in, and
            // needs no normalizing, so it exercises the source end to end
            Config config = config(
                "rwc.sources = env",
                "rwc.env.type = environmentVariables",
                "rwc.env.ignoreUnknownProperties = true",
                "PATH"
            );
            assertEquals(System.getenv("PATH"), config.gets("PATH"));
        }

        @Test
        void aPropertiesFile() throws IOException {
            Config config = config(
                "rwc.sources = local",
                "rwc.local.type = properties",
                "rwc.local.location = " + propertiesFile("app.properties", "myProp=fromAPropertiesFile"),
                "myProp"
            );
            assertEquals("fromAPropertiesFile", config.gets("myProp"));
        }

        @Test
        @DisplayName("a directory source uses file names as property names and contents as values")
        void aDirectory() throws IOException {
            Path directory = tempDir.resolve("confdir");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("greeting"), "Hello, ");
            Files.writeString(directory.resolve("name"), "World");
            Config config = config(
                "rwc.sources = dir",
                "rwc.dir.type = directory",
                "rwc.dir.path = " + directory,
                "greeting",
                "name"
            );
            // the docs promise that surrounding whitespace in the file is kept
            assertEquals("Hello, ", config.gets("greeting"));
            assertEquals("World", config.gets("name"));
        }

        @Test
        @DisplayName("declaring the command line source without passing the arguments is an error")
        void commandLineArgumentsWithoutArguments() throws IOException {
            Path file = tempDir.resolve("rwconfig");
            Files.write(file, List.of(
                "rwc.sources = args",
                "rwc.args.type = commandLineArguments",
                "myProp = a"
            ));
            System.setProperty(ConfigFactory.CONFIG_FILE_PATH_PROPERTY, "file:" + file);
            systemPropertiesSet.add(ConfigFactory.CONFIG_FILE_PATH_PROPERTY);
            assertThrows(ConfigException.class, () -> ConfigFactory.create());
        }
    }


    @Nested
    @DisplayName("sources are prioritized in the order they are declared")
    class Precedence {

        @Test
        void anEarlierSourceWins() throws IOException {
            Config config = config(
                "rwc.sources = first, second",
                "rwc.first.type = properties",
                "rwc.first.location = " + propertiesFile("first.properties", "myProp=fromFirst"),
                "rwc.second.type = properties",
                "rwc.second.location = " + propertiesFile("second.properties", "myProp=fromSecond"),
                "myProp"
            );
            assertEquals("fromFirst", config.gets("myProp"));
        }

        @Test
        void aSourceOverridesTheDefaultInTheConfigFile() throws IOException {
            Config config = config(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myProp=fromTheSource"),
                "myProp = fromTheConfigFile"
            );
            assertEquals("fromTheSource", config.gets("myProp"));
        }

        @Test
        void aDefaultIsUsedWhenNoSourceSetsTheProperty() throws IOException {
            Config config = config(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "somethingElse=x"),
                "rwc.only.ignoreUnknownProperties = true",
                "myProp = fromTheConfigFile"
            );
            assertEquals("fromTheConfigFile", config.gets("myProp"));
        }

        @Test
        @DisplayName("a property with no default must be set by some source")
        void aPropertyWithNoValueAnywhereIsRejected() throws IOException {
            ConfigException e = rejected(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "somethingElse=x"),
                "rwc.only.ignoreUnknownProperties = true",
                "myProp"
            );
            assertTrue(
                e.getMessage().contains("is not set by any config source"),
                "expected a `not set by any config source` error, but got: " + e.getMessage()
            );
        }
    }


    @Nested
    @DisplayName("unknown properties in a source")
    class UnknownProperties {

        @Test
        @DisplayName("are an error by default, which catches typos in property names")
        void areRejectedByDefault() throws IOException {
            ConfigException e = rejected(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myProp=a", "notDeclared=b"),
                "myProp"
            );
            assertTrue(
                e.getMessage().contains("not defined in the `rwconfig` file"),
                "expected an `unknown property` error, but got: " + e.getMessage()
            );
        }

        @Test
        void areIgnoredWhenTheSourceSaysSo() throws IOException {
            Config config = config(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myProp=a", "notDeclared=b"),
                "rwc.only.ignoreUnknownProperties = true",
                "myProp"
            );
            assertEquals("a", config.gets("myProp"));
            assertTrue(!config.has("notDeclared"), "an ignored property should not be in the config");
        }
    }


    @Nested
    @DisplayName("a `<<` backreference takes its value from an earlier source")
    class Backreferences {

        @Test
        void theValueComesFromAnEarlierSource() throws IOException {
            Config config = config(
                "rwc.sources = secrets, main",
                "rwc.secrets.type = properties",
                "rwc.secrets.location = " + propertiesFile("s.properties", "rwc.main.location=" + propertiesFile("m.properties", "myProp=loadedViaBackreference")),
                "rwc.main.type = properties",
                "rwc.main.location = <<",
                "myProp"
            );
            assertEquals("loadedViaBackreference", config.gets("myProp"));
        }

        @Test
        void anUnresolvedBackreferenceIsRejected() throws IOException {
            ConfigException e = rejected(
                "rwc.sources = main",
                "rwc.main.type = properties",
                "rwc.main.location = <<",
                "myProp = a"
            );
            assertTrue(
                e.getMessage().contains("backreference"),
                "expected a `backreference` error, but got: " + e.getMessage()
            );
        }
    }


    @Nested
    @DisplayName("declaring the sources themselves")
    class SourceDeclarations {

        @Test
        void theSourcesPropertyIsRequired() throws IOException {
            ConfigException e = rejected("myProp = a");
            assertTrue(
                e.getMessage().contains("missing config property"),
                "expected a `missing config property` error, but got: " + e.getMessage()
            );
        }

        @Test
        void aSourceMustDeclareItsType() throws IOException {
            ConfigException e = rejected("rwc.sources = only", "myProp = a");
            assertTrue(
                e.getMessage().contains("missing required config property"),
                "expected a `missing required` error, but got: " + e.getMessage()
            );
        }

        @Test
        void anUnknownSourceTypeIsRejected() throws IOException {
            rejected("rwc.sources = only", "rwc.only.type = notARealType", "myProp = a");
        }

        @Test
        void aSourceMissingARequiredPropertyIsRejected() throws IOException {
            // a `properties` source has to say where to load the file from
            ConfigException e = rejected(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "myProp = a"
            );
            assertTrue(
                e.getMessage().contains("missing required config property"),
                "expected a `missing required` error, but got: " + e.getMessage()
            );
        }

        @Test
        void aDuplicateSourceNameIsRejected() throws IOException {
            ConfigException e = rejected(
                "rwc.sources = only, only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myProp=a"),
                "myProp"
            );
            assertTrue(
                e.getMessage().contains("duplicate config source"),
                "expected a `duplicate config source` error, but got: " + e.getMessage()
            );
        }

        @Test
        void anEmptySourceNameIsRejected() throws IOException {
            rejected("rwc.sources = only, , other", "rwc.only.type = systemProperties", "myProp = a");
        }

        @Test
        @DisplayName("a source that cannot be loaded is reported rather than ignored")
        void anUnreadableSourceIsRejected() throws IOException {
            ConfigException e = rejected(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = file:" + tempDir.resolve("does-not-exist.properties"),
                "myProp = a"
            );
            assertTrue(
                e.getMessage().contains("error loading properties"),
                "expected a load error, but got: " + e.getMessage()
            );
        }
    }


    @Nested
    @DisplayName("values coming from a source are validated the same way defaults are")
    class ValuesFromSources {

        @Test
        void theTypeIsChecked() throws IOException {
            rejected(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myProp=notAnInt"),
                "int myProp = 1"
            );
        }

        @Test
        void theAllowedValuesAreChecked() throws IOException {
            ConfigException e = rejected(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myProp=500"),
                "int[0:100] myProp = 50"
            );
            assertTrue(
                e.getMessage().contains("is not allowed"),
                "expected a `not allowed` error, but got: " + e.getMessage()
            );
        }

        @Test
        void aListFromASourceIsParsed() throws IOException {
            Config config = config(
                "rwc.sources = only",
                "rwc.only.type = properties",
                "rwc.only.location = " + propertiesFile("o.properties", "myList=1, 2, 3"),
                "intList myList = 9"
            );
            assertEquals(List.of(1, 2, 3), config.getil("myList"));
        }
    }


    @Nested
    @DisplayName("the config root")
    class ConfigRoot {

        @Test
        void defaultsToRwc() throws IOException {
            Config config = config(
                "rwc.sources = sys",
                "rwc.sys.type = systemProperties",
                "rwc.sys.ignoreUnknownProperties = true",
                "myProp = a"
            );
            assertEquals("a", config.gets("myProp"));
            assertTrue(!config.has("rwc.sources"), "config setup lines should not be application properties");
        }

        @Test
        void canBeChanged() throws IOException {
            Config config = config(
                "rwc.root = myapp.",
                "myapp.sources = sys",
                "myapp.sys.type = systemProperties",
                "myapp.sys.ignoreUnknownProperties = true",
                "myProp = a"
            );
            assertEquals("a", config.gets("myProp"));
            assertTrue(!config.has("rwc.root"), "the config root declaration is not an application property");
            assertTrue(!config.has("myapp.sources"), "config setup lines should not be application properties");
        }
    }
}
