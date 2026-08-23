package net.rabbitware.config.plugin.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for flattening a JSON source into properties.
 *
 * <p>Most of these cover which arrays are collapsed into a single
 * comma-separated value and which fall back to indexed names. Note that the
 * check cannot look at the element classes alone: the JSON parser hands back a
 * {@code BigDecimal} for a decimal, a {@code BigInteger} for a large whole
 * number, and a sentinel object (not a Java {@code null}) for {@code null}.
 */
class JsonPluginTest {

    @TempDir
    private Path tempDir;

    /** Flatten the given JSON into properties. */
    private Map<String, String> load(String json) throws Exception {
        Path file = tempDir.resolve("source.json");
        Files.writeString(file, json);
        JsonPlugin plugin = new JsonPlugin();
        plugin.setSourceName("test");
        plugin.setPluginProperties(Map.of("location", "file:" + file));
        return plugin.getConfigSourceProperties();
    }

    /** Flatten a JSON document holding the single array `x`. */
    private Map<String, String> loadArray(String elements) throws Exception {
        return load("{\"x\": " + elements + "}");
    }


    @Nested
    @DisplayName("an array of one primitive type becomes a comma-separated list")
    class Collapsed {

        @Test
        void strings() throws Exception {
            assertEquals("a,b,c", loadArray("[\"a\",\"b\",\"c\"]").get("x"));
        }

        @Test
        void integers() throws Exception {
            assertEquals("1,2,3", loadArray("[1,2,3]").get("x"));
        }

        @Test
        @DisplayName("decimals - the parser returns BigDecimal for these, not Double")
        void decimals() throws Exception {
            assertEquals("1.5,2.5,3.5", loadArray("[1.5,2.5,3.5]").get("x"));
        }

        @Test
        void negativeNumbers() throws Exception {
            assertEquals("-1,-2", loadArray("[-1,-2]").get("x"));
            assertEquals("-1.5,-2.5", loadArray("[-1.5,-2.5]").get("x"));
        }

        @Test
        void scientificNotation() throws Exception {
            assertEquals("1.0E+5,2.0E+5", loadArray("[1.0e5,2.0e5]").get("x"));
        }

        @Test
        @DisplayName("whole numbers too large for a long - the parser returns BigInteger")
        void veryLargeIntegers() throws Exception {
            assertEquals(
                "99999999999999999999,88888888888888888888",
                loadArray("[99999999999999999999,88888888888888888888]").get("x")
            );
        }

        @Test
        void decimalsOutsideDoubleRange() throws Exception {
            assertEquals("1E+400,2E+400", loadArray("[1e400,2e400]").get("x"));
        }

        @Test
        void booleans() throws Exception {
            assertEquals("true,false", loadArray("[true,false]").get("x"));
        }

        @Test
        @DisplayName("an empty array is an empty value, which reads back as an empty list")
        void emptyArray() throws Exception {
            assertEquals("", loadArray("[]").get("x"));
        }

        @Test
        void aSingleElement() throws Exception {
            assertEquals("1.5", loadArray("[1.5]").get("x"));
        }

        @Test
        @DisplayName("nulls are treated as strings, since a null reads back as the string `null`")
        void nulls() throws Exception {
            assertEquals("null,null", loadArray("[null,null]").get("x"));
            assertEquals("a,null,c", loadArray("[\"a\",null,\"c\"]").get("x"));
        }
    }


    @Nested
    @DisplayName("an array of mixed or non-primitive values keeps indexed names")
    class Indexed {

        @Test
        void integersMixedWithDecimals() throws Exception {
            Map<String, String> properties = loadArray("[1,2.5,3]");
            assertNull(properties.get("x"), "the array should not have been collapsed");
            assertEquals("1", properties.get("x\\0"));
            assertEquals("2.5", properties.get("x\\1"));
            assertEquals("3", properties.get("x\\2"));
        }

        @Test
        @DisplayName("a whole number written as a decimal still counts as a decimal")
        void integersMixedWithWholeDecimals() throws Exception {
            assertNull(loadArray("[1,2.0]").get("x"));
        }

        @Test
        void stringsMixedWithNumbers() throws Exception {
            assertNull(loadArray("[\"a\",1]").get("x"));
        }

        @Test
        void numbersMixedWithNulls() throws Exception {
            Map<String, String> properties = loadArray("[1,null,3]");
            assertNull(properties.get("x"));
            assertEquals("null", properties.get("x\\1"));
        }

        @Test
        void objects() throws Exception {
            Map<String, String> properties = loadArray("[{\"a\":1},{\"a\":2}]");
            assertNull(properties.get("x"));
            assertEquals("1", properties.get("x\\0\\a"));
            assertEquals("2", properties.get("x\\1\\a"));
        }

        @Test
        void nestedArrays() throws Exception {
            Map<String, String> properties = loadArray("[[1,2],[3,4]]");
            assertNull(properties.get("x"));
            assertEquals("1,2", properties.get("x\\0"));
            assertEquals("3,4", properties.get("x\\1"));
        }

        @Test
        void booleansMixedWithStrings() throws Exception {
            assertNull(loadArray("[true,\"a\"]").get("x"));
        }
    }


    @Nested
    @DisplayName("keys are renamed so that flattening cannot produce a conflict")
    class KeyNaming {

        @Test
        @DisplayName("a literal backslash in a key is escaped, so a nested key and a literal one differ")
        void backslashesInKeysAreEscaped() throws Exception {
            // PLUGINS.md: {"a": {"b": "wazoo"}, "a\\b": "..."} -> a\b and a\\b
            Map<String, String> properties = load("{\"a\": {\"b\": \"wazoo\"}, \"a\\\\b\": \"literal\"}");
            assertEquals("wazoo", properties.get("a\\b"));
            assertEquals("literal", properties.get("a\\\\b"));
        }

        @Test
        @DisplayName("an empty key is renamed to `empty\\key`")
        void emptyKeysAreRenamed() throws Exception {
            assertEquals("value", load("{\"a\": {\"\": \"value\"}}").get("a\\empty\\key"));
        }

        @Test
        void anEmptyKeyAtTheTopLevel() throws Exception {
            assertEquals("value", load("{\"\": \"value\"}").get("empty\\key"));
        }
    }


    @Test
    @DisplayName("the example from PLUGINS.md produces the properties it documents")
    void theDocumentedExample() throws Exception {
        Map<String, String> properties = load("""
            {
              "strings": ["a", "b", "c"],
              "ints": [1, 2, 3, 4, 5],
              "floats": [1.5, 2.5, 3.5],
              "mixed": [1, 2.5, 3]
            }
            """);
        assertEquals("a,b,c", properties.get("strings"));
        assertEquals("1,2,3,4,5", properties.get("ints"));
        assertEquals("1.5,2.5,3.5", properties.get("floats"));
        assertEquals("1", properties.get("mixed\\0"));
        assertEquals("2.5", properties.get("mixed\\1"));
        assertEquals("3", properties.get("mixed\\2"));
        assertNull(properties.get("mixed"));
    }

    @Test
    @DisplayName("a scalar null is the string `null`")
    void aScalarNull() throws Exception {
        assertEquals("null", load("{\"x\": null}").get("x"));
    }

    @Test
    void nestedObjectsAreFlattenedWithBackslashes() throws Exception {
        Map<String, String> properties = load("{\"a\": {\"b\": {\"c\": \"deep\"}}}");
        assertEquals("deep", properties.get("a\\b\\c"));
    }

    @Test
    @DisplayName("the reported version is the one the jar was built at, not a literal in the source")
    void versionTracksTheBuild() {
        // `API_VERSION` is filtered in from the pom at build time. Comparing to it
        // catches a plugin that has gone back to hardcoding a version of its own.
        assertEquals(SimpleConfigSourcePlugin.API_VERSION, new JsonPlugin().getPluginVersion());
        assertTrue(SimpleConfigSourcePlugin.API_VERSION.matches("\\d+(\\.\\d+)*"),
            "should look like a version, but was: " + SimpleConfigSourcePlugin.API_VERSION);
        assertNotEquals("0.0.0", SimpleConfigSourcePlugin.API_VERSION,
            "0.0.0 is the fallback used when version.properties is missing from the jar");
    }

    @Nested
    @DisplayName("whether changes can be detected depends on the location")
    class ChangeDetection {

        private boolean supportedFor(String location) throws Exception {
            JsonPlugin plugin = new JsonPlugin();
            plugin.setPluginProperties(Map.of("location", location));
            return plugin.isChangeDetectionSupported();
        }

        @Test
        @DisplayName("a location that can be watched or polled supports it")
        void watchableLocations() throws Exception {
            assertEquals(true, supportedFor("file:/tmp/x.txt"), "file");
            assertEquals(true, supportedFor("jar:file:/tmp/a.jar!/x.txt"), "jar");
            assertEquals(true, supportedFor("http://example.com/x.txt"), "http");
            assertEquals(true, supportedFor("https://example.com/x.txt"), "https");
        }

        @Test
        @DisplayName("a classpath resource does not - it cannot change while the JVM runs")
        void classpathIsNotWatchable() throws Exception {
            assertEquals(false, supportedFor("classpath:x.txt"));
        }

        @Test
        @DisplayName("nor does a plugin that has not been given a location yet")
        void unconfiguredPluginSaysNo() {
            assertEquals(false, new JsonPlugin().isChangeDetectionSupported());
        }
    }
}
