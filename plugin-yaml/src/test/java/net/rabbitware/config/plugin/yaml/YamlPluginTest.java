package net.rabbitware.config.plugin.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for flattening a YAML source into properties.
 *
 * <p>Most of these cover which sequences are collapsed into a single
 * comma-separated value and which fall back to indexed names. Unlike the JSON
 * and XML sources, a YAML null arrives as a real Java {@code null}, so it has
 * to be handled without tripping over it.
 */
class YamlPluginTest {

    @TempDir
    private Path tempDir;

    /** Flatten the given YAML into properties. */
    private Map<String, String> load(String yaml) throws Exception {
        Path file = tempDir.resolve("source.yaml");
        Files.writeString(file, yaml);
        YamlPlugin plugin = new YamlPlugin();
        plugin.setSourceName("test");
        plugin.setPluginProperties(Map.of("location", "file:" + file));
        return plugin.getConfigSourceProperties();
    }

    /** Flatten a YAML document holding the single sequence `x`. */
    private Map<String, String> loadSequence(String elements) throws Exception {
        return load("x: " + elements + "\n");
    }


    @Nested
    @DisplayName("a sequence of one primitive type becomes a comma-separated list")
    class Collapsed {

        @Test
        void strings() throws Exception {
            assertEquals("a,b,c", loadSequence("[a, b, c]").get("x"));
        }

        @Test
        void integers() throws Exception {
            assertEquals("1,2,3", loadSequence("[1, 2, 3]").get("x"));
        }

        @Test
        void decimals() throws Exception {
            assertEquals("1.5,2.5,3.5", loadSequence("[1.5, 2.5, 3.5]").get("x"));
        }

        @Test
        void negativeNumbers() throws Exception {
            assertEquals("-1,-2", loadSequence("[-1, -2]").get("x"));
            assertEquals("-1.5,-2.5", loadSequence("[-1.5, -2.5]").get("x"));
        }

        @Test
        @DisplayName("whole numbers too large for a long - the parser returns BigInteger")
        void veryLargeIntegers() throws Exception {
            assertEquals(
                "99999999999999999999,88888888888888888888",
                loadSequence("[99999999999999999999, 88888888888888888888]").get("x")
            );
        }

        @Test
        void booleans() throws Exception {
            assertEquals("true,false", loadSequence("[true, false]").get("x"));
        }

        @Test
        @DisplayName("an empty sequence is an empty value, which reads back as an empty list")
        void emptySequence() throws Exception {
            assertEquals("", loadSequence("[]").get("x"));
        }

        @Test
        void aSingleElement() throws Exception {
            assertEquals("1.5", loadSequence("[1.5]").get("x"));
        }

        @Test
        @DisplayName("nulls are treated as strings, since a null reads back as the string `null`")
        void nulls() throws Exception {
            assertEquals("null,null", loadSequence("[null, null]").get("x"));
            assertEquals("a,null,c", loadSequence("[a, null, c]").get("x"));
        }

        @Test
        @DisplayName("a block sequence collapses the same way a flow sequence does")
        void blockStyleSequences() throws Exception {
            assertEquals("1.5,2.5", load("x:\n  - 1.5\n  - 2.5\n").get("x"));
        }
    }


    @Nested
    @DisplayName("a sequence of mixed or non-primitive values keeps indexed names")
    class Indexed {

        @Test
        void integersMixedWithDecimals() throws Exception {
            Map<String, String> properties = loadSequence("[1, 2.5, 3]");
            assertNull(properties.get("x"), "the sequence should not have been collapsed");
            assertEquals("1", properties.get("x\\0"));
            assertEquals("2.5", properties.get("x\\1"));
            assertEquals("3", properties.get("x\\2"));
        }

        @Test
        void stringsMixedWithNumbers() throws Exception {
            assertNull(loadSequence("[a, 1]").get("x"));
        }

        @Test
        void numbersMixedWithNulls() throws Exception {
            Map<String, String> properties = loadSequence("[1, null, 3]");
            assertNull(properties.get("x"));
            assertEquals("null", properties.get("x\\1"));
        }

        @Test
        void mappings() throws Exception {
            Map<String, String> properties = load("x:\n  - a: 1\n  - a: 2\n");
            assertNull(properties.get("x"));
            assertEquals("1", properties.get("x\\0\\a"));
            assertEquals("2", properties.get("x\\1\\a"));
        }

        @Test
        void nestedSequences() throws Exception {
            Map<String, String> properties = loadSequence("[[1, 2], [3, 4]]");
            assertNull(properties.get("x"));
            assertEquals("1,2", properties.get("x\\0"));
            assertEquals("3,4", properties.get("x\\1"));
        }

        @Test
        void booleansMixedWithStrings() throws Exception {
            assertNull(loadSequence("[true, a]").get("x"));
        }
    }


    @Test
    @DisplayName("the example from PLUGINS.md produces the properties it documents")
    void theDocumentedExample() throws Exception {
        Map<String, String> properties = load("""
            strings: [a, b, c]
            ints:
              - 1
              - 2
              - 3
              - 4
              - 5
            floats: [1.5, 2.5, 3.5]
            mixed: [1, 2.5, 3]
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
        assertEquals("null", load("x: null\n").get("x"));
    }

    @Test
    void nestedMappingsAreFlattenedWithBackslashes() throws Exception {
        assertEquals("deep", load("a:\n  b:\n    c: deep\n").get("a\\b\\c"));
    }
}
