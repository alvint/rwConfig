package net.rabbitware.config.plugin.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for flattening a YAML source into properties.
 *
 * <p>Most of these cover which sequences are collapsed into a single
 * comma-separated value and which fall back to indexed names. A sequence of
 * plain values collapses whatever mix of types it holds, and a mapping, a
 * nested sequence, or a value the plugin cannot write out - such as one tagged
 * {@code !!binary} - keeps indexed names. Unlike the JSON
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
    @DisplayName("a sequence of plain values becomes a comma-separated list")
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

        @Test
        @DisplayName("whole numbers and decimals together - `[9.99, 10]` is an ordinary price list")
        void integersMixedWithDecimals() throws Exception {
            assertEquals("1,2.5,3", loadSequence("[1, 2.5, 3]").get("x"));
            assertEquals("9.99,10", loadSequence("[9.99, 10]").get("x"));
        }

        @Test
        @DisplayName("strings, numbers, booleans, and nulls together - a `stringList` can read any of them")
        void anyMixOfPlainValues() throws Exception {
            assertEquals("a,1", loadSequence("[a, 1]").get("x"));
            assertEquals("true,a", loadSequence("[true, a]").get("x"));
            assertEquals("1,null,3", loadSequence("[1, null, 3]").get("x"));
        }
    }


    @Nested
    @DisplayName("a sequence holding a mapping or another sequence keeps indexed names")
    class Indexed {

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
        @DisplayName("one mapping among plain values is enough")
        void oneMappingAmongPlainValues() throws Exception {
            Map<String, String> properties = loadSequence("[1, {a: 2}]");
            assertNull(properties.get("x"));
            assertEquals("1", properties.get("x\\0"));
            assertEquals("2", properties.get("x\\1\\a"));
        }

        @Test
        @DisplayName("a tagged value the plugin cannot write out is skipped, rather than joined as junk")
        void taggedValuesAreNotJoined() throws Exception {
            // `!!binary` is a byte array and `!!set` a Set - joined, they came out
            // as `[B@51016012` and `[a, b]`. Indexed, each is skipped with a
            // warning, the same as outside a sequence
            Map<String, String> properties = loadSequence("[!!binary aGVsbG8=, plain]");
            assertNull(properties.get("x"));
            assertNull(properties.get("x\\0"));
            assertEquals("plain", properties.get("x\\1"));
            properties = loadSequence("[!!set {a, b}, plain]");
            assertNull(properties.get("x"));
            assertNull(properties.get("x\\0"));
            assertEquals("plain", properties.get("x\\1"));
        }
    }


    @Nested
    @DisplayName("keys are renamed so that flattening cannot produce a conflict")
    class KeyNaming {

        @Test
        @DisplayName("a literal backslash in a key is escaped, so a nested key and a literal one differ")
        void backslashesInKeysAreEscaped() throws Exception {
            Map<String, String> properties = load("a:\n  b: wazoo\n\"a\\\\b\": literal\n");
            assertEquals("wazoo", properties.get("a\\b"));
            assertEquals("literal", properties.get("a\\\\b"));
        }

        @Test
        @DisplayName("an empty key is renamed to `empty\\key`")
        void emptyKeysAreRenamed() throws Exception {
            assertEquals("value", load("a:\n  \"\": value\n").get("a\\empty\\key"));
        }

        @Test
        @DisplayName("a null key is renamed to `null\\key`")
        void nullKeysAreRenamed() throws Exception {
            assertEquals("value", load("a:\n  ?\n  : value\n").get("a\\null\\key"));
        }
    }


    @Nested
    @DisplayName("merge keys")
    class MergeKeys {

        private static final String MERGE_YAML = """
            defaults: &defaults
              retries: 3
              timeout-seconds: 10
            mergeKeyTest:
              <<: *defaults
              queue: jobs
            """;

        @Test
        @DisplayName("are resolved by default, so the `<<` does not appear in property names")
        void areResolvedByDefault() throws Exception {
            Map<String, String> properties = load(MERGE_YAML);
            assertEquals("jobs", properties.get("mergeKeyTest\\queue"));
            assertEquals("3", properties.get("mergeKeyTest\\retries"));
            assertEquals("10", properties.get("mergeKeyTest\\timeout-seconds"));
            assertNull(properties.get("mergeKeyTest\\<<\\retries"));
        }

        @Test
        @DisplayName("can be left in the graph, which is closer to the YAML 1.2 spec")
        void canBeLeftUnresolved() throws Exception {
            Path file = tempDir.resolve("source.yaml");
            Files.writeString(file, MERGE_YAML);
            YamlPlugin plugin = new YamlPlugin();
            plugin.setSourceName("test");
            plugin.setPluginProperties(Map.of("location", "file:" + file, "resolveMergeKeys", "false"));
            Map<String, String> properties = plugin.getConfigSourceProperties();
            assertEquals("jobs", properties.get("mergeKeyTest\\queue"));
            assertEquals("3", properties.get("mergeKeyTest\\<<\\retries"));
            assertNull(properties.get("mergeKeyTest\\retries"));
        }

        @Test
        void anInvalidResolveMergeKeysValueIsRejected() throws Exception {
            Path file = tempDir.resolve("source.yaml");
            Files.writeString(file, "a: 1\n");
            YamlPlugin plugin = new YamlPlugin();
            plugin.setSourceName("test");
            assertThrows(
                Exception.class,
                () -> plugin.setPluginProperties(Map.of("location", "file:" + file, "resolveMergeKeys", "maybe"))
            );
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
            prices: [9.99, 10, 12.5]
            servers:
              - port: 80
              - port: 443
            """);
        assertEquals("a,b,c", properties.get("strings"));
        assertEquals("1,2,3,4,5", properties.get("ints"));
        assertEquals("9.99,10,12.5", properties.get("prices"));
        assertEquals("80", properties.get("servers\\0\\port"));
        assertEquals("443", properties.get("servers\\1\\port"));
        assertNull(properties.get("servers"));
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

    @Nested
    @DisplayName("a decimal arrives exactly as written")
    class Decimals {

        @Test
        @DisplayName("digits a double cannot hold are kept")
        void precisionIsKept() throws Exception {
            // through a double this was `1.0`, before a `bigDecimal` property
            // ever saw it
            assertEquals("1.00000000000000000001", load("d: 1.00000000000000000001\n").get("d"));
        }

        @Test
        @DisplayName("a trailing zero is kept, since it is part of the scale")
        void scaleIsKept() throws Exception {
            assertEquals("10.50", load("m: 10.50\n").get("m"));
        }

        @Test
        @DisplayName("in a list too")
        void listItemsAreKept() throws Exception {
            assertEquals("1.50,2.00", loadSequence("[1.50, 2.00]").get("x"));
        }

        @Test
        @DisplayName("an exponent is written in Java's form - `1e3` arrives as `1E+3`")
        void exponentForm() throws Exception {
            // the value is exact, but the text is BigDecimal's. It still parses
            // as a `double` or a `bigDecimal`
            assertEquals("1E+3", load("e: 1e3\n").get("e"));
        }

        @Test
        @DisplayName("the special values are unchanged")
        void specialValues() throws Exception {
            Map<String, String> properties = load("a: .inf\nb: -.inf\nc: .nan\n");
            assertEquals("Infinity", properties.get("a"));
            assertEquals("-Infinity", properties.get("b"));
            assertEquals("NaN", properties.get("c"));
        }

        @Test
        @DisplayName("an integer too large for a long is kept whole")
        void largeIntegerIsKept() throws Exception {
            assertEquals(
                "123456789012345678901234567890",
                load("i: 123456789012345678901234567890\n").get("i"));
        }
    }


    @Nested
    @DisplayName("whether changes can be detected depends on the location")
    class ChangeDetection {

        private boolean supportedFor(String location) throws Exception {
            YamlPlugin plugin = new YamlPlugin();
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
            assertEquals(false, new YamlPlugin().isChangeDetectionSupported());
        }
    }


    @Nested
    @DisplayName("optional properties")
    class OptionalProperties {

        @Test
        @DisplayName("the plugin's own property does not displace the inherited ones")
        void ownPropertyIsAddedToTheInheritedOnes() {
            // returning only `resolveMergeKeys` here would mean `username` and
            // `password` were never collected for a YAML source, so HTTP
            // credentials would be silently dropped
            assertEquals(
                java.util.Set.of("username", "password", "resolveMergeKeys"),
                new YamlPlugin().getOptionalPluginPropertyNames());
        }

        @Test
        @DisplayName("credentials reach the plugin")
        void credentialsAreAccepted() throws Exception {
            Path file = tempDir.resolve("source.yaml");
            Files.writeString(file, "a: 1\n");
            YamlPlugin plugin = new YamlPlugin();
            plugin.setSourceName("test");
            Map<String, String> properties = new HashMap<>();
            properties.put("location", "file:" + file);
            properties.put("password", "hunter2");
            properties.put("username", null);
            properties.put("resolveMergeKeys", null);
            // a password with no username is rejected, which only happens if
            // the setting was collected and handed over in the first place
            Exception e = assertThrows(Exception.class, () -> plugin.setPluginProperties(properties));
            assertTrue(
                String.valueOf(e.getMessage()).contains("username"),
                "expected the missing-username error, but got: " + e.getMessage()
            );
        }
    }
}
