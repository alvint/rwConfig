package net.rabbitware.config.plugin.hocon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for flattening a HOCON source into properties.
 *
 * <p>The flattening rules are the same as the JSON plugin's, so the bulk of
 * these mirror {@code JsonPluginTest}: which lists collapse into a single
 * comma-separated value, which fall back to indexed names, and how keys are
 * rewritten so that flattening cannot produce a conflict.
 *
 * <p>The rest cover what HOCON adds - substitutions, object merging, dotted
 * paths - and, just as importantly, the two things it does <em>not</em> do
 * here: {@code include} and substitution against system properties. Both are
 * pinned by tests so that a change in behavior shows up as a failure rather
 * than as a surprise in production.
 */
class HoconPluginTest {

    @TempDir
    private Path tempDir;

    /** Flatten the given HOCON into properties. */
    private Map<String, String> load(String hocon) throws Exception {
        Path file = tempDir.resolve("source.conf");
        Files.writeString(file, hocon);
        return loadFile(file);
    }

    /** Flatten the given file, which may sit alongside others. */
    private Map<String, String> loadFile(Path file) throws Exception {
        HoconPlugin plugin = new HoconPlugin();
        plugin.setSourceName("test");
        plugin.setPluginProperties(Map.of("location", "file:" + file));
        return plugin.getConfigSourceProperties();
    }

    /** Flatten a document holding the single list `x`. */
    private Map<String, String> loadList(String elements) throws Exception {
        return load("x = " + elements);
    }


    @Nested
    @DisplayName("a list of one primitive type becomes a comma-separated list")
    class Collapsed {

        @Test
        void strings() throws Exception {
            assertEquals("a,b,c", loadList("[a, b, c]").get("x"));
        }

        @Test
        void quotedStrings() throws Exception {
            assertEquals("a,b,c", loadList("[\"a\", \"b\", \"c\"]").get("x"));
        }

        @Test
        void integers() throws Exception {
            assertEquals("1,2,3", loadList("[1, 2, 3]").get("x"));
        }

        @Test
        void decimals() throws Exception {
            assertEquals("1.5,2.5,3.5", loadList("[1.5, 2.5, 3.5]").get("x"));
        }

        @Test
        void negativeNumbers() throws Exception {
            assertEquals("-1.5,-2.5", loadList("[-1.5, -2.5]").get("x"));
        }

        @Test
        @DisplayName("scientific notation - HOCON normalizes to a whole number where JSON keeps a decimal")
        void scientificNotation() throws Exception {
            // the JSON plugin yields `1.0E+5,2.0E+5` for the same input, because
            // its parser hands back a BigDecimal. Typesafe Config unwraps these
            // to a Long instead, so they collapse as integers
            assertEquals("100000,200000", loadList("[1.0e5, 2.0e5]").get("x"));
        }

        @Test
        void booleans() throws Exception {
            assertEquals("true,false", loadList("[true, false]").get("x"));
        }

        @Test
        @DisplayName("an empty list is an empty value, which reads back as an empty list")
        void emptyList() throws Exception {
            assertEquals("", loadList("[]").get("x"));
        }

        @Test
        void aSingleElement() throws Exception {
            assertEquals("1.5", loadList("[1.5]").get("x"));
        }

        @Test
        @DisplayName("nulls are treated as strings, since a null reads back as the string `null`")
        void nulls() throws Exception {
            assertEquals("a,null", loadList("[a, null]").get("x"));
        }
    }


    @Nested
    @DisplayName("a list of mixed or non-primitive values keeps indexed names")
    class Indexed {

        @Test
        void integersMixedWithDecimals() throws Exception {
            Map<String, String> properties = loadList("[1, 2.5, 3]");
            assertNull(properties.get("x"), "the list should not have been collapsed");
            assertEquals("1", properties.get("x\\0"));
            assertEquals("2.5", properties.get("x\\1"));
            assertEquals("3", properties.get("x\\2"));
        }

        @Test
        void stringsMixedWithNumbers() throws Exception {
            Map<String, String> properties = loadList("[a, 1]");
            assertNull(properties.get("x"));
            assertEquals("a", properties.get("x\\0"));
            assertEquals("1", properties.get("x\\1"));
        }

        @Test
        void booleansMixedWithStrings() throws Exception {
            Map<String, String> properties = loadList("[true, a]");
            assertNull(properties.get("x"));
            assertEquals("true", properties.get("x\\0"));
        }

        @Test
        void objects() throws Exception {
            Map<String, String> properties = loadList("[{a = 1}, {a = 2}]");
            assertNull(properties.get("x"));
            assertEquals("1", properties.get("x\\0\\a"));
            assertEquals("2", properties.get("x\\1\\a"));
        }

        @Test
        void nestedLists() throws Exception {
            Map<String, String> properties = loadList("[[1, 2], [3, 4]]");
            assertNull(properties.get("x"));
            assertEquals("1,2", properties.get("x\\0"));
            assertEquals("3,4", properties.get("x\\1"));
        }
    }


    @Nested
    @DisplayName("keys are renamed so that flattening cannot produce a conflict")
    class KeyNaming {

        @Test
        @DisplayName("a literal backslash in a key is escaped, so a nested key and a literal one differ")
        void backslashesInKeysAreEscaped() throws Exception {
            Map<String, String> properties = load("a { b = wazoo }\n\"a\\\\b\" = literal\n");
            assertEquals("wazoo", properties.get("a\\b"), "the nested value");
            assertEquals("literal", properties.get("a\\\\b"), "the key that held a backslash");
        }

        @Test
        @DisplayName("an empty key is renamed to `empty\\key`")
        void emptyKeysAreRenamed() throws Exception {
            assertEquals("1", load("a { \"\" = 1 }").get("a\\empty\\key"));
        }

        @Test
        void anEmptyKeyAtTheTopLevel() throws Exception {
            assertEquals("1", load("\"\" = 1").get("empty\\key"));
        }
    }


    @Nested
    @DisplayName("what HOCON adds over JSON")
    class HoconFeatures {

        @Test
        @DisplayName("a dotted key is a path, and nests - unlike a quoted one, which is literal")
        void dottedKeysNest() throws Exception {
            Map<String, String> properties = load("a.b = nested\n\"c.d\" = literal\n");
            assertEquals("nested", properties.get("a\\b"), "a dotted key describes a path");
            assertEquals("literal", properties.get("c.d"), "a quoted dotted key is one name");
        }

        @Test
        @DisplayName("substitutions within the document are resolved")
        void substitutionsAreResolved() throws Exception {
            assertEquals("8000", load("base = 8000\nport = ${base}\n").get("port"));
        }

        @Test
        @DisplayName("an unresolvable substitution fails rather than yielding a blank")
        void unresolvedSubstitutionsFail() throws Exception {
            assertThrows(Exception.class, () -> load("port = ${MISSING}\n"));
        }

        @Test
        @DisplayName("an optional substitution that resolves to nothing drops the property")
        void optionalSubstitutionsAreDropped() throws Exception {
            Map<String, String> properties = load("port = ${?NOT_SET_ANYWHERE}\nother = 1\n");
            assertNull(properties.get("port"));
            assertEquals("1", properties.get("other"));
        }

        @Test
        @DisplayName("objects with the same name are merged, not replaced")
        void objectsAreMerged() throws Exception {
            Map<String, String> properties = load("a { x = 1 }\na { y = 2 }\n");
            assertEquals("1", properties.get("a\\x"));
            assertEquals("2", properties.get("a\\y"));
        }

        @Test
        @DisplayName("a repeated scalar key takes the last value")
        void laterScalarsWin() throws Exception {
            assertEquals("2", load("a = 1\na = 2\n").get("a"));
        }

        @Test
        @DisplayName("numbers are normalized - `1.0` arrives as `1`, but a quoted value is untouched")
        void numbersAreNormalised() throws Exception {
            Map<String, String> properties =
                load("a = 1.0\nb = 100.0\nc = 2.0e3\nd = 1.25\ne = \"1.0\"\n");
            assertEquals("1", properties.get("a"), "a redundant fractional part is dropped");
            assertEquals("100", properties.get("b"));
            assertEquals("2000", properties.get("c"), "exponents are expanded");
            assertEquals("1.25", properties.get("d"), "a real fraction is left alone");
            assertEquals("1.0", properties.get("e"), "quoting keeps the exact characters");
        }

        @Test
        @DisplayName("`5s` and `10M` are plain strings - the notation is a reader convention, not a type")
        void unitsAreNotInterpreted() throws Exception {
            Map<String, String> properties = load("timeout = 5s\nsize = 10M\n");
            assertEquals("5s", properties.get("timeout"));
            assertEquals("10M", properties.get("size"));
        }

        @Test
        void commentsAndTripleQuotedStringsAreHandled() throws Exception {
            Map<String, String> properties = load("# hash comment\n// slash comment\nkey = \"\"\"a\nb\"\"\"\n");
            assertEquals("a\nb", properties.get("key"));
        }
    }


    @Nested
    @DisplayName("what this plugin deliberately does not do")
    class Limitations {

        @Test
        @DisplayName("`include` does not pull in a neighbouring file")
        void includesAreNotResolvedRelativeToTheSource() throws Exception {
            // the source is read to a string before being parsed, so it has no
            // origin for a relative include to resolve against. An optional
            // include therefore finds nothing and is silently skipped - every
            // source rwConfig reads has to be one it was told about in the
            // `rwconfig` file, so this is the intended outcome rather than a
            // gap, but it does surprise people arriving from Typesafe Config
            Files.writeString(tempDir.resolve("other.conf"), "fromInclude = yes\n");
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include \"other.conf\"\nx = 1\n");
            Map<String, String> properties = loadFile(main);
            assertEquals("1", properties.get("x"));
            assertNull(properties.get("fromInclude"), "the include should not have been resolved");
        }

        @Test
        @DisplayName("a required `include` fails outright")
        void requiredIncludesFail() throws Exception {
            Files.writeString(tempDir.resolve("other.conf"), "fromInclude = yes\n");
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include required(\"other.conf\")\nx = 1\n");
            Exception e = assertThrows(Exception.class, () -> loadFile(main));
            assertTrue(
                String.valueOf(e.getMessage()).contains("other.conf"),
                "the error should name the file it could not find, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("substitutions do not reach system properties")
        void systemPropertiesAreNotSubstituted() throws Exception {
            // resolution is confined to the document, so a source cannot quietly
            // pick up values from outside it. Use rwConfig's own
            // `systemProperties` source, with its declared precedence, instead
            System.setProperty("hoconPluginTestProperty", "fromSystem");
            try {
                Map<String, String> properties = load("a = ${?hoconPluginTestProperty}\nb = 1\n");
                assertNull(properties.get("a"), "the system property should not have been visible");
                assertEquals("1", properties.get("b"));
            } finally {
                System.clearProperty("hoconPluginTestProperty");
            }
        }
    }


    @Test
    @DisplayName("the example from PLUGINS.md produces the properties it documents")
    void theDocumentedExample() throws Exception {
        Map<String, String> properties = load("""
            numberOfAccounts = 2
            accounts = [
              { name = alvin, role = admin },
              { name = carl,  role = user  }
            ]
            """);
        assertEquals("2", properties.get("numberOfAccounts"));
        assertEquals("alvin", properties.get("accounts\\0\\name"));
        assertEquals("admin", properties.get("accounts\\0\\role"));
        assertEquals("carl", properties.get("accounts\\1\\name"));
        assertEquals("user", properties.get("accounts\\1\\role"));
        assertNull(properties.get("accounts"), "the list itself is structure, not a property");
    }


    @Nested
    @DisplayName("plugin plumbing")
    class Plumbing {

        @Test
        void requiredAndOptionalPropertyNames() {
            HoconPlugin plugin = new HoconPlugin();
            assertEquals(java.util.Set.of("location"), plugin.getRequiredPluginPropertyNames());
            // inherited from LocationBasedConfigSourcePlugin: a source with a
            // location can also be given HTTP credentials for it
            assertEquals(
                java.util.Set.of("username", "password"), plugin.getOptionalPluginPropertyNames());
        }

        @Test
        void aMissingLocationIsRejected() {
            HoconPlugin plugin = new HoconPlugin();
            assertThrows(Exception.class, () -> plugin.setPluginProperties(Map.of()));
        }

        @Test
        void anUnsupportedLocationIsRejected() {
            HoconPlugin plugin = new HoconPlugin();
            assertThrows(Exception.class, () -> plugin.setPluginProperties(Map.of("location", "ftp://nope/x.conf")));
        }

        @Nested
        @DisplayName("whether changes can be detected depends on the location")
        class ChangeDetection {

            private boolean supportedFor(String location) throws Exception {
                HoconPlugin plugin = new HoconPlugin();
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
                assertEquals(false, new HoconPlugin().isChangeDetectionSupported());
            }
        }

        @Test
        void versionIsReported() {
            assertTrue(new HoconPlugin().getPluginVersion().matches("\\d+(\\.\\d+)*"));
        }
    }
}
