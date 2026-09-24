package net.rabbitware.config.plugin.hocon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Tests for flattening a HOCON source into properties.
 *
 * <p>The flattening rules are the same as the JSON plugin's, so the bulk of
 * these mirror {@code JsonPluginTest}: which lists collapse into a single
 * comma-separated value, which fall back to indexed names, and how keys are
 * rewritten so that flattening cannot produce a conflict.
 *
 * <p>The rest cover what HOCON adds - substitutions, object merging, dotted
 * paths - and the line the {@code trusted} property draws. Untrusted is the
 * default and confines a document to itself: no {@code include} reaches the
 * file system, the classpath, or the network, and a substitution sees only
 * what the document declares. Trusted turns all of it back on. Both sides are
 * pinned by tests, because the untrusted side is a security boundary and a
 * change to it should show up as a failure rather than as a surprise in
 * production.
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

    /** Flatten the given file, which may sit alongside others, with any extra settings as name/value pairs. */
    private Map<String, String> loadFile(Path file, String... extraProperties) throws Exception {
        HoconPlugin plugin = new HoconPlugin();
        plugin.setSourceName("test");
        Map<String, String> properties = new HashMap<>();
        properties.put("location", "file:" + file);
        for (int i = 0; i < extraProperties.length; i += 2) {
            properties.put(extraProperties[i], extraProperties[i + 1]);
        }
        plugin.setPluginProperties(properties);
        return plugin.getConfigSourceProperties();
    }

    /** Flatten a document holding the single list `x`. */
    private Map<String, String> loadList(String elements) throws Exception {
        return load("x = " + elements);
    }


    @Nested
    @DisplayName("a list of plain values becomes a comma-separated list")
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
        @DisplayName("scientific notation is kept as written")
        void scientificNotation() throws Exception {
            // Typesafe Config unwraps these to a Long, which would have made
            // them `100000,200000` - the text is what was written
            assertEquals("1.0e5,2.0e5", loadList("[1.0e5, 2.0e5]").get("x"));
        }

        @Test
        @DisplayName("decimals whose fraction is zero keep it")
        void decimalsWithZeroFraction() throws Exception {
            // unwrapped, `2.00` is the integer `2` - the text is what was written
            assertEquals("1.50,2.00", loadList("[1.50, 2.00]").get("x"));
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

        @Test
        @DisplayName("whole numbers and decimals together - `[9.99, 10]` is an ordinary price list")
        void integersMixedWithDecimals() throws Exception {
            assertEquals("1,2.5,3", loadList("[1, 2.5, 3]").get("x"));
            assertEquals("9.99,10", loadList("[9.99, 10]").get("x"));
        }

        @Test
        @DisplayName("an item's comma and leading space are escaped, so the item reads back whole")
        void itemsAreEscaped() throws Exception {
            // the escaping itself is ListValues' - see ListValuesTest in the config module
            assertEquals("a\\,b,\\e c", loadList("[\"a,b\", \" c\"]").get("x"));
        }

        @Test
        @DisplayName("strings, numbers, and booleans together - a `stringList` can read any of them")
        void anyMixOfPlainValues() throws Exception {
            assertEquals("a,1", loadList("[a, 1]").get("x"));
            assertEquals("true,a", loadList("[true, a]").get("x"));
        }
    }


    @Nested
    @DisplayName("a list holding an object or another list keeps indexed names")
    class Indexed {

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

        @Test
        @DisplayName("one object among plain values is enough")
        void oneObjectAmongPlainValues() throws Exception {
            Map<String, String> properties = loadList("[1, {a = 2}]");
            assertNull(properties.get("x"));
            assertEquals("1", properties.get("x\\0"));
            assertEquals("2", properties.get("x\\1\\a"));
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
        @DisplayName("numbers arrive exactly as written, not as Typesafe Config normalizes them")
        void numbersAreKeptAsWritten() throws Exception {
            Map<String, String> properties =
                load("a = 1.0\nb = 100.0\nc = 2.0e3\nd = 1.25\ne = \"1.0\"\n");
            assertEquals("1.0", properties.get("a"), "a zero fraction is kept");
            assertEquals("100.0", properties.get("b"));
            assertEquals("2.0e3", properties.get("c"), "an exponent is not expanded");
            assertEquals("1.25", properties.get("d"));
            assertEquals("1.0", properties.get("e"), "a quoted value is unchanged");
        }

        @Test
        @DisplayName("a decimal keeps digits a double cannot hold")
        void decimalPrecisionIsKept() throws Exception {
            Map<String, String> properties = load("d = 1.00000000000000000001\nm = 10.50\n");
            assertEquals("1.00000000000000000001", properties.get("d"));
            assertEquals("10.50", properties.get("m"), "the trailing zero is part of the scale");
        }

        @Test
        @DisplayName("an integer too large for a long is kept whole")
        void largeIntegerIsKept() throws Exception {
            assertEquals(
                "123456789012345678901234567890",
                load("i = 123456789012345678901234567890\n").get("i"));
        }

        @Test
        @DisplayName("a substitution copies a number exactly as it was written")
        void substitutionKeepsText() throws Exception {
            assertEquals("10.50", load("m = 10.50\ncopy = ${m}\n").get("copy"));
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
    @DisplayName("an untrusted source - the default - is confined to its own document")
    class Untrusted {

        @Test
        @DisplayName("a relative `include` is refused, and says how to allow it")
        void relativeIncludeIsRefused() throws Exception {
            // Typesafe Config resolves a bare include against the process
            // working directory and then the classpath, so this is a real
            // reach outside the document even though the source was parsed
            // from a string with no origin of its own
            Files.writeString(tempDir.resolve("other.conf"), "fromInclude = yes\n");
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include \"other.conf\"\nx = 1\n");
            Exception e = assertThrows(Exception.class, () -> loadFile(main));
            assertTrue(
                String.valueOf(e.getMessage()).contains("trusted"),
                "the error should point at `trusted`, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("`include file()` does not read the file it names")
        void fileIncludeIsRefused() throws Exception {
            Path other = tempDir.resolve("other.conf");
            Files.writeString(other, "fromInclude = yes\n");
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include file(\"" + other + "\")\nx = 1\n");
            Exception e = assertThrows(Exception.class, () -> loadFile(main));
            assertTrue(
                String.valueOf(e.getMessage()).contains("trusted"),
                "the error should point at `trusted`, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("`include url()` does not make the request")
        void urlIncludeIsRefused() throws Exception {
            // port 1 has nothing listening, so a connection error here instead
            // of our own message would mean the request had been attempted
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include url(\"http://127.0.0.1:1/other.conf\")\nx = 1\n");
            Exception e = assertThrows(Exception.class, () -> loadFile(main));
            assertTrue(
                String.valueOf(e.getMessage()).contains("trusted"),
                "the request should have been refused rather than attempted, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("`include classpath()` is refused too")
        void classpathIncludeIsRefused() throws Exception {
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include classpath(\"other.conf\")\nx = 1\n");
            Exception e = assertThrows(Exception.class, () -> loadFile(main));
            assertTrue(
                String.valueOf(e.getMessage()).contains("trusted"),
                "the error should point at `trusted`, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("an optional substitution does not reach environment variables")
        void environmentIsNotSubstituted() throws Exception {
            // Typesafe Config consults the environment by default when
            // resolving. An untrusted source turns that off, so a source
            // cannot quietly pull in values from outside itself - use
            // rwConfig's own `environmentVariables` source, whose precedence
            // you declare
            Map<String, String> properties = load("a = ${?PATH}\nb = 1\n");
            assertNull(properties.get("a"), "the environment variable should not have been visible");
            assertEquals("1", properties.get("b"));
        }

        @Test
        @DisplayName("a required substitution on an environment variable fails")
        void requiredEnvironmentSubstitutionFails() throws Exception {
            Exception e = assertThrows(Exception.class, () -> load("a = ${PATH}\n"));
            assertTrue(
                String.valueOf(e.getMessage()).contains("PATH"),
                "the error should name the substitution, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("substitutions do not reach system properties")
        void systemPropertiesAreNotSubstituted() throws Exception {
            // looking them up is a reach outside the document, the same as
            // the environment, so only a trusted source does it
            System.setProperty("hoconPluginTestProperty", "fromSystem");
            try {
                Map<String, String> properties = load("a = ${?hoconPluginTestProperty}\nb = 1\n");
                assertNull(properties.get("a"), "the system property should not have been visible");
                assertEquals("1", properties.get("b"));
            } finally {
                System.clearProperty("hoconPluginTestProperty");
            }
        }

        @Test
        @DisplayName("a substitution within the document still resolves")
        void inDocumentSubstitutionStillWorks() throws Exception {
            assertEquals("8000", load("base = 8000\nport = ${base}\n").get("port"));
        }
    }


    @Nested
    @DisplayName("a trusted source gets all of HOCON")
    class Trusted {

        @Test
        @DisplayName("`include file()` reads the file")
        void fileIncludeWorks() throws Exception {
            Path other = tempDir.resolve("other.conf");
            Files.writeString(other, "fromInclude = yes\n");
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include file(\"" + other + "\")\nx = 1\n");
            Map<String, String> properties = loadFile(main, "trusted", "true");
            assertEquals("1", properties.get("x"));
            assertEquals("yes", properties.get("fromInclude"));
        }

        @Test
        @DisplayName("substitutions reach environment variables")
        void environmentIsSubstituted() throws Exception {
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "a = ${?PATH}\n");
            Map<String, String> properties = loadFile(main, "trusted", "true");
            assertNotNull(properties.get("a"), "PATH should have been substituted");
        }

        @Test
        @DisplayName("substitutions reach system properties")
        void systemPropertiesAreSubstituted() throws Exception {
            System.setProperty("hoconPluginTestProperty", "fromSystem");
            try {
                Path main = tempDir.resolve("main.conf");
                Files.writeString(main, "a = ${?hoconPluginTestProperty}\nb = 1\n");
                Map<String, String> properties = loadFile(main, "trusted", "true");
                assertEquals("fromSystem", properties.get("a"));
                assertEquals("1", properties.get("b"));
            } finally {
                System.clearProperty("hoconPluginTestProperty");
            }
        }

        @Test
        @DisplayName("a system property can be part of a larger value")
        void systemPropertyInConcatenation() throws Exception {
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "logs = ${user.home}/logs\n");
            Map<String, String> properties = loadFile(main, "trusted", "true");
            assertEquals(System.getProperty("user.home") + "/logs", properties.get("logs"));
        }

        @Test
        @DisplayName("system properties fill in substitutions but are not added to the source")
        void systemPropertiesAreNotMerged() throws Exception {
            // `ConfigFactory.load` would merge every system property into the
            // result, which rwConfig would report as dozens of unknown
            // properties - only the document's own keys may come back
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "home = ${user.home}\n");
            Map<String, String> properties = loadFile(main, "trusted", "true");
            assertEquals(Map.of("home", System.getProperty("user.home")), properties);
        }

        @Test
        @DisplayName("the document wins over a system property at the same path")
        void documentWinsOverSystemProperty() throws Exception {
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "java { version = mine }\nv = ${java.version}\n");
            Map<String, String> properties = loadFile(main, "trusted", "true");
            assertEquals("mine", properties.get("java\\version"));
            assertEquals("mine", properties.get("v"));
        }

        @Test
        @DisplayName("`java.version` is a string, not an object holding `java.version.date`")
        void javaVersionIsAString() throws Exception {
            // since Java 11 both are set, and parsed as a tree the longer name
            // turns the shorter into an object - Typesafe Config drops the
            // `java.version.*` keys to prevent that, and so must we
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "v = ${java.version}\n");
            Map<String, String> properties = loadFile(main, "trusted", "true");
            assertEquals(Map.of("v", System.getProperty("java.version")), properties);
        }

        @Test
        @DisplayName("a system property set after an earlier load is still seen")
        void systemPropertiesAreReadFresh() throws Exception {
            // Typesafe Config caches `ConfigFactory.systemProperties()` for the
            // life of the JVM, so reading through it would miss this
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "a = ${?hoconPluginTestLateProperty}\n");
            assertNull(loadFile(main, "trusted", "true").get("a"));
            System.setProperty("hoconPluginTestLateProperty", "late");
            try {
                assertEquals("late", loadFile(main, "trusted", "true").get("a"));
            } finally {
                System.clearProperty("hoconPluginTestLateProperty");
            }
        }
    }


    @Nested
    @DisplayName("the `trusted` property itself")
    class TrustedProperty {

        private Map<String, String> loadWith(String trusted) throws Exception {
            Path main = tempDir.resolve("main.conf");
            Path other = tempDir.resolve("other.conf");
            Files.writeString(other, "fromInclude = yes\n");
            Files.writeString(main, "include file(\"" + other + "\")\nx = 1\n");
            return loadFile(main, "trusted", trusted);
        }

        @Test
        @DisplayName("defaults to false when the setting is absent")
        void defaultsToFalse() throws Exception {
            Path main = tempDir.resolve("main.conf");
            Files.writeString(main, "include file(\"" + tempDir.resolve("other.conf") + "\")\nx = 1\n");
            assertThrows(Exception.class, () -> loadFile(main));
        }

        @Test
        @DisplayName("takes the same spellings as any other boolean setting")
        void acceptsBooleanSpellings() throws Exception {
            assertEquals("yes", loadWith("yes").get("fromInclude"));
            assertEquals("yes", loadWith("on").get("fromInclude"));
            assertEquals("yes", loadWith("1").get("fromInclude"));
            assertThrows(Exception.class, () -> loadWith("no"));
            assertThrows(Exception.class, () -> loadWith("off"));
        }

        @Test
        @DisplayName("rejects a value that is not a boolean")
        void rejectsNonBoolean() {
            Exception e = assertThrows(Exception.class, () -> loadWith("maybe"));
            assertTrue(
                String.valueOf(e.getMessage()).contains("maybe"),
                "the error should name the bad value, but got: " + e.getMessage()
            );
        }
    }


    @Nested
    @DisplayName("plugin plumbing")
    class Plumbing {

        @Test
        void requiredAndOptionalPropertyNames() {
            HoconPlugin plugin = new HoconPlugin();
            assertEquals(java.util.Set.of("location"), plugin.getRequiredPluginPropertyNames());
            // `username` and `password` are inherited from
            // LocationBasedConfigSourcePlugin - a source with a location can
            // also be given HTTP credentials for it - and have to survive this
            // plugin adding one of its own
            assertEquals(
                java.util.Set.of("username", "password", "trusted"),
                plugin.getOptionalPluginPropertyNames());
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
