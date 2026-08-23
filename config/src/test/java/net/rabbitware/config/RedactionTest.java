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
        Path values = tempDir.resolve("app.properties");
        Files.writeString(values, property + "=9999\n");
        Path file = tempDir.resolve("rwconfig");
        List<String> lines = new ArrayList<>(List.of(
            "rwc.sources = f",
            "rwc.f.type = properties",
            "rwc.f.location = file:" + values));
        lines.addAll(Arrays.asList(librarySettings));
        lines.add("int[1..100] " + property + " = 5");
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
