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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.rabbitware.config.Config.ConfigException;

/**
 * Tests for keeping values out of error messages.
 *
 * <p>Two rules, and either is enough: a source declared secret, and - unless
 * turned off - a property whose name reads like a secret. The messages here are
 * the ones that carry a value, which is how a password reaches a log file.
 */
class RedactionTest {

    @TempDir
    private Path tempDir;

    /** Build a config whose one property fails its range check, and return the error. */
    private String errorFor(String property, String... librarySettings) throws IOException {
        return rangeError("int", property, librarySettings);
    }

    /** The same, for a property of the given type. */
    private String rangeError(String type, String property, String... librarySettings) throws IOException {
        Path values = tempDir.resolve("app.properties");
        Files.writeString(values, property + "=9999\n");
        Path file = tempDir.resolve("rwconfig");
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = f",
            "rwc.f.type = properties",
            "rwc.f.location = file:" + values));
        lines.addAll(Arrays.asList(librarySettings));
        lines.add(type + "[1..100] " + property + " = 5");
        Files.write(file, lines);
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigFactory.create(
            new String[] { ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file }));
        return e.getMessage();
    }

    @Nested
    @DisplayName("judging by the property's name")
    class ByName {

        @ParameterizedTest
        @ValueSource(strings = {
            "password", "dbPassword", "clientSecret", "authToken", "credentials", "apiKey", "privateKey"
        })
        @DisplayName("a name that reads like a secret has its value withheld")
        void secretNames(String property) throws IOException {
            assertTrue(errorFor(property).contains("****"), property + " should have been redacted");
        }

        @ParameterizedTest
        @ValueSource(strings = { "port", "hostname", "keyCount", "keyStore", "timeout" })
        @DisplayName("an ordinary name still shows its value, which is what makes the error useful")
        void ordinaryNames(String property) throws IOException {
            String message = errorFor(property);
            assertTrue(message.contains("9999"), property + " should have shown its value: " + message);
            assertFalse(message.contains("****"), message);
        }

        @ParameterizedTest
        @ValueSource(strings = { "bigInteger", "bigDecimal", "bigIntegerList", "bigDecimalList" })
        @DisplayName("the big number types, which check their ranges separately, withhold it too")
        void bigNumberTypes(String type) throws IOException {
            String message = rangeError(type, "apiSecret");
            assertTrue(message.contains("****"), type + " should have been redacted: " + message);
            assertFalse(message.contains("9999"), message);
        }

        @Test
        @DisplayName("`key` counts only after something else, so `apiKey` is secret and `keyCount` is not")
        void keyOnlyCountsAsASuffix() throws IOException {
            assertTrue(errorFor("apiKey").contains("****"));
            assertTrue(errorFor("keyCount").contains("9999"));
        }

        @Test
        @DisplayName("it can be turned off")
        void canBeTurnedOff() throws IOException {
            assertTrue(errorFor("dbPassword", "rwc.redactSecretsByName = false").contains("9999"));
        }
    }

    @Nested
    @DisplayName("a value that cannot be parsed is withheld too")
    class ParseErrors {

        /**
         * Load a value that fails to parse, from a system property, and return
         * the error. A system property is taken as it is, so the value reaches
         * the parser exactly as written here.
         */
        private ConfigException parseError(String declaration, String value) throws IOException {
            String name = declaration.substring(declaration.lastIndexOf(' ') + 1);
            Path file = tempDir.resolve("rwconfig");
            Files.write(file, List.of("rwc.sources = sys", "rwc.sys.type = systemProperties", declaration));
            System.setProperty(name, value);
            try {
                return assertThrows(ConfigException.class, () -> ConfigFactory.create(
                    new String[] { ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file }));
            } finally {
                System.clearProperty(name);
            }
        }

        /** Every message in the chain - a logged stack trace prints them all. */
        private List<String> messages(Throwable e) {
            List<String> messages = new ArrayList<>();
            for (Throwable t = e; t != null; t = t.getCause()) {
                messages.add(t.getClass().getName() + ": " + t.getMessage());
            }
            return messages;
        }

        private void assertWithheld(String declaration, String value) throws IOException {
            ConfigException e = parseError(declaration, value);
            assertTrue(e.getMessage().contains("****"), declaration + " should have been redacted: " + e.getMessage());
            for (String message : messages(e)) {
                assertFalse(message.contains(value), declaration + " leaked its value in: " + message);
            }
        }

        @Test
        @DisplayName("a number that is not one - including the parser's own exception, kept as the cause")
        void aNumber() throws IOException {
            assertWithheld("int apiSecret", "12x34");
            assertWithheld("bigDecimal apiSecret", "12x34");
        }

        @Test
        @DisplayName("a timestamp that is not one, or is finer than a millisecond")
        void aTimestamp() throws IOException {
            assertWithheld("timestamp apiSecret", "not-a-date-12x34");
            assertWithheld("timestamp apiSecret", "2026-08-17T00:00:00.0001234Z");
        }

        @Test
        @DisplayName("a duration or size with a unit that does not exist, or too large to hold")
        void aDurationOrSize() throws IOException {
            assertWithheld("duration apiSecret", "5parsecs");
            assertWithheld("size apiSecret", "99999999999999999TB");
        }

        @Test
        @DisplayName("a list item with an escape sequence that is not one")
        void anEscapeSequence() throws IOException {
            assertWithheld("stringList apiSecret", "a\\qb");
            assertWithheld("stringList apiSecret", "abc\\");
        }

        @Test
        @DisplayName("a misplaced escaped space, which is only a warning, is not logged either")
        void anEscapedSpaceWarning() throws IOException {
            java.io.PrintStream original = System.err;
            var captured = new java.io.ByteArrayOutputStream();
            Path file = tempDir.resolve("rwconfig");
            Files.write(file, List.of("rwc.sources = sys", "rwc.sys.type = systemProperties",
                "stringList apiSecret = none"));
            System.setProperty("apiSecret", "hunter2\\ x");
            try {
                System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
                ConfigFactory.create(new String[] { ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file });
            } finally {
                System.setErr(original);
                System.clearProperty("apiSecret");
            }
            String log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(log.contains("escaped space is only meaningful"), "expected the warning, but got: " + log);
            assertFalse(log.contains("hunter2"), log);
        }

        @Test
        @DisplayName("an ordinary property still shows its value, and keeps the parser's exception")
        void anOrdinaryPropertyStillShowsItsValue() throws IOException {
            ConfigException e = parseError("int port", "12x34");
            assertTrue(e.getMessage().contains("12x34"), e.getMessage());
            assertTrue(e.getCause() instanceof NumberFormatException, "the cause is still useful: " + e.getCause());
        }
    }


    @Nested
    @DisplayName("declaring a whole source secret")
    class BySource {

        @Test
        @DisplayName("withholds every value from it, whatever the property is called")
        void everythingFromASecretSource() throws IOException {
            assertTrue(errorFor("port", "rwc.f.secret = true").contains("****"),
                "a source declared secret covers the properties nobody thought to name carefully");
        }

        @Test
        @DisplayName("is off unless asked for")
        void offByDefault() throws IOException {
            assertTrue(errorFor("port").contains("9999"));
        }

        @Test
        @DisplayName("still applies when the name heuristic is off - the two are independent")
        void independentOfTheHeuristic() throws IOException {
            assertTrue(
                errorFor("port", "rwc.f.secret = true", "rwc.redactSecretsByName = false").contains("****"));
        }
    }

    @Nested
    @DisplayName("the decision itself")
    class Decision {

        @Test
        @DisplayName("a secret source wins regardless of the name")
        void sourceWins() {
            assertTrue(ConfigFactory.isSecret("port", "vault", java.util.Set.of("vault"), false));
        }

        @Test
        @DisplayName("a different source is not covered")
        void otherSourcesUnaffected() {
            assertFalse(ConfigFactory.isSecret("port", "plain", java.util.Set.of("vault"), false));
        }

        @Test
        @DisplayName("a value from the `rwconfig` file itself has no source, so only the name applies")
        void noSource() {
            assertTrue(ConfigFactory.isSecret("dbPassword", null, java.util.Set.of(), true));
            assertFalse(ConfigFactory.isSecret("dbPassword", null, java.util.Set.of(), false));
        }

        @Test
        @DisplayName("names are matched by whole words, in any of the usual separators")
        void separators() {
            for (String name : new String[] { "db.password", "db_password", "DB_PASSWORD", "db-password" }) {
                assertTrue(ConfigFactory.isSecret(name, null, java.util.Set.of(), true), name);
            }
        }
    }

    @Test
    @DisplayName("a redacted message still says what is wrong and where")
    void theMessageIsStillUseful() throws IOException {
        String message = errorFor("dbPassword");
        assertTrue(message.contains("dbPassword"), message);
        assertTrue(message.contains("source `f`"), message);
        assertEquals(false, message.contains("9999"), message);
    }
}
