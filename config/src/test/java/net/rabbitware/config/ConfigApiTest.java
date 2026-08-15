package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.rabbitware.config.Config.IncorrectTypeException;
import net.rabbitware.config.Config.PropertyNotFoundException;
import net.rabbitware.config.Config.PropertyType;

/**
 * Tests for the {@link Config} interface - the part of the library that
 * application code actually calls.
 *
 * <p>The README promises that a wrong property name or a wrong type is always
 * an error rather than something the caller has to check for, so those cases
 * matter as much as the successful reads.
 */
class ConfigApiTest {

    @TempDir
    private Path tempDir;

    private Config config;

    /** One config holding a property of every supported type. */
    @BeforeEach
    void loadConfig() throws IOException {
        config = configFrom(
            "boolean myBoolean = true",
            "int myInt = 42",
            "long myLong = 5000000000",
            "double myDouble = 1.5",
            "string myString = hello",
            "booleanList myBooleanList = true, false",
            "intList myIntList = 1, 2, 3",
            "longList myLongList = 5000000000, 1",
            "doubleList myDoubleList = 1.5, 2.5",
            "stringList myStringList = a, b, c"
        );
    }

    private Config configFrom(String... propertyLines) throws IOException {
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = sys",
            "rwc.sys.type = systemProperties",
            "rwc.sys.ignoreUnknownProperties = true"
        ));
        lines.addAll(Arrays.asList(propertyLines));
        Path file = tempDir.resolve("rwconfig");
        Files.write(file, lines);
        return ConfigFactory.create(new String[] {
            ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file
        });
    }


    @Nested
    @DisplayName("reading a value of each type")
    class Reads {

        @Test
        void scalars() {
            assertTrue(config.getBoolean("myBoolean"));
            assertEquals(42, config.getInt("myInt"));
            assertEquals(5000000000L, config.getLong("myLong"));
            assertEquals(1.5, config.getDouble("myDouble"), 1e-9);
            assertEquals("hello", config.getString("myString"));
        }

        @Test
        void lists() {
            assertEquals(List.of(true, false), config.getBooleanList("myBooleanList"));
            assertEquals(List.of(1, 2, 3), config.getIntList("myIntList"));
            assertEquals(List.of(5000000000L, 1L), config.getLongList("myLongList"));
            assertEquals(List.of(1.5, 2.5), config.getDoubleList("myDoubleList"));
            assertEquals(List.of("a", "b", "c"), config.getStringList("myStringList"));
        }

        @Test
        @DisplayName("the short aliases return the same values as the long names")
        void shortAliases() {
            assertEquals(config.getBoolean("myBoolean"), config.getb("myBoolean"));
            assertEquals(config.getInt("myInt"), config.geti("myInt"));
            assertEquals(config.getLong("myLong"), config.getl("myLong"));
            assertEquals(config.getDouble("myDouble"), config.getd("myDouble"), 1e-9);
            assertEquals(config.getString("myString"), config.gets("myString"));
            assertEquals(config.getBooleanList("myBooleanList"), config.getbl("myBooleanList"));
            assertEquals(config.getIntList("myIntList"), config.getil("myIntList"));
            assertEquals(config.getLongList("myLongList"), config.getll("myLongList"));
            assertEquals(config.getDoubleList("myDoubleList"), config.getdl("myDoubleList"));
            assertEquals(config.getStringList("myStringList"), config.getsl("myStringList"));
        }
    }


    @Nested
    @DisplayName("asking about properties")
    class Metadata {

        @Test
        void hasReportsWhetherAPropertyIsDeclared() {
            assertTrue(config.has("myInt"));
            assertFalse(config.has("nothingCalledThis"));
        }

        @Test
        void getTypeReportsTheDeclaredType() {
            assertEquals(PropertyType.BOOLEAN, config.getType("myBoolean"));
            assertEquals(PropertyType.INT, config.getType("myInt"));
            assertEquals(PropertyType.LONG, config.getType("myLong"));
            assertEquals(PropertyType.DOUBLE, config.getType("myDouble"));
            assertEquals(PropertyType.STRING, config.getType("myString"));
            assertEquals(PropertyType.BOOLEAN_LIST, config.getType("myBooleanList"));
            assertEquals(PropertyType.INT_LIST, config.getType("myIntList"));
            assertEquals(PropertyType.LONG_LIST, config.getType("myLongList"));
            assertEquals(PropertyType.DOUBLE_LIST, config.getType("myDoubleList"));
            assertEquals(PropertyType.STRING_LIST, config.getType("myStringList"));
        }

        @Test
        void getPropertyNamesListsEveryDeclaredProperty() {
            assertEquals(
                Set.of(
                    "myBoolean", "myInt", "myLong", "myDouble", "myString",
                    "myBooleanList", "myIntList", "myLongList", "myDoubleList", "myStringList"
                ),
                config.getPropertyNames()
            );
        }

        @Test
        @DisplayName("config setup properties are not exposed to the application")
        void configSetupPropertiesAreNotIncluded() {
            assertFalse(config.getPropertyNames().stream().anyMatch(name -> name.startsWith("rwc.")));
        }
    }


    @Nested
    @DisplayName("mistakes in application code are always errors, never silent")
    class Errors {

        @Test
        void readingAnUndeclaredPropertyThrows() {
            assertThrows(PropertyNotFoundException.class, () -> config.gets("nothingCalledThis"));
            assertThrows(PropertyNotFoundException.class, () -> config.geti("nothingCalledThis"));
            assertThrows(PropertyNotFoundException.class, () -> config.getType("nothingCalledThis"));
        }

        @Test
        void readingAPropertyAsTheWrongTypeThrows() {
            assertThrows(IncorrectTypeException.class, () -> config.geti("myString"));
            assertThrows(IncorrectTypeException.class, () -> config.gets("myInt"));
            assertThrows(IncorrectTypeException.class, () -> config.getb("myInt"));
            assertThrows(IncorrectTypeException.class, () -> config.getd("myInt"));
            assertThrows(IncorrectTypeException.class, () -> config.getl("myString"));
        }

        @Test
        @DisplayName("a scalar cannot be read as a list, and a list cannot be read as a scalar")
        void mixingScalarsAndListsThrows() {
            assertThrows(IncorrectTypeException.class, () -> config.getil("myInt"));
            assertThrows(IncorrectTypeException.class, () -> config.geti("myIntList"));
            assertThrows(IncorrectTypeException.class, () -> config.getsl("myString"));
        }

        @Test
        @DisplayName("a list cannot be read as a list of a different type")
        void readingAListAsTheWrongListTypeThrows() {
            assertThrows(IncorrectTypeException.class, () -> config.getil("myStringList"));
            assertThrows(IncorrectTypeException.class, () -> config.getsl("myIntList"));
            assertThrows(IncorrectTypeException.class, () -> config.getdl("myIntList"));
        }

        @Test
        @DisplayName("these are unchecked, so callers are not forced to handle them")
        void theErrorsAreUnchecked() {
            assertTrue(RuntimeException.class.isAssignableFrom(Config.ConfigException.class));
            assertTrue(Config.ConfigException.class.isAssignableFrom(PropertyNotFoundException.class));
            assertTrue(Config.ConfigException.class.isAssignableFrom(IncorrectTypeException.class));
        }
    }


    @Nested
    @DisplayName("the config is effectively immutable")
    class Immutability {

        @Test
        void aReturnedListCannotBeModified() {
            List<Integer> values = config.getil("myIntList");
            assertThrows(UnsupportedOperationException.class, () -> values.add(4));
        }

        @Test
        @DisplayName("the set of property names is a copy, so changing it cannot change the config")
        void theSetOfPropertyNamesIsACopy() {
            Set<String> names = config.getPropertyNames();
            names.add("somethingNew");
            names.remove("myInt");
            assertFalse(config.has("somethingNew"), "adding to the returned set must not add a property");
            assertTrue(config.has("myInt"), "removing from the returned set must not remove a property");
            assertTrue(config.getPropertyNames().contains("myInt"));
            assertFalse(config.getPropertyNames().contains("somethingNew"));
        }

        @Test
        void readingTheSamePropertyTwiceGivesTheSameValue() {
            assertEquals(config.geti("myInt"), config.geti("myInt"));
            assertEquals(config.getil("myIntList"), config.getil("myIntList"));
        }
    }


    @Nested
    @DisplayName("empty and edge-case values")
    class EdgeCases {

        @Test
        void anEmptyStringValue() throws IOException {
            assertEquals("", configFrom("string myProp =").gets("myProp"));
        }

        @Test
        void emptyListsOfEachType() throws IOException {
            Config c = configFrom(
                "intList myInts =",
                "stringList myStrings =",
                "booleanList myBooleans =",
                "doubleList myDoubles =",
                "longList myLongs ="
            );
            assertEquals(List.of(), c.getil("myInts"));
            assertEquals(List.of(), c.getsl("myStrings"));
            assertEquals(List.of(), c.getbl("myBooleans"));
            assertEquals(List.of(), c.getdl("myDoubles"));
            assertEquals(List.of(), c.getll("myLongs"));
        }

        @Test
        void negativeAndBoundaryNumbers() throws IOException {
            Config c = configFrom(
                "int myMin = " + Integer.MIN_VALUE,
                "int myMax = " + Integer.MAX_VALUE,
                "long myLongMin = " + Long.MIN_VALUE,
                "long myLongMax = " + Long.MAX_VALUE,
                "double myNegative = -1.5"
            );
            assertEquals(Integer.MIN_VALUE, c.geti("myMin"));
            assertEquals(Integer.MAX_VALUE, c.geti("myMax"));
            assertEquals(Long.MIN_VALUE, c.getl("myLongMin"));
            assertEquals(Long.MAX_VALUE, c.getl("myLongMax"));
            assertEquals(-1.5, c.getd("myNegative"), 1e-9);
        }

        @Test
        @DisplayName("a number too large for its type is rejected at startup")
        void numbersOutsideTheirTypeRange() throws IOException {
            assertThrows(
                Config.ConfigException.class,
                () -> configFrom("int myProp = " + (Integer.MAX_VALUE + 1L))
            );
        }
    }
}
