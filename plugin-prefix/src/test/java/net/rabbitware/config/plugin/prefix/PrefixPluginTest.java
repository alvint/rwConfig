package net.rabbitware.config.plugin.prefix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the prefix plugin, which loads a properties file and puts the
 * source name in front of every property name it found.
 */
class PrefixPluginTest {

    @TempDir
    private Path tempDir;

    /** Load the given properties file content under the given source name. */
    private Map<String, String> load(String sourceName, String... propertyLines) throws Exception {
        Path file = tempDir.resolve("source.properties");
        Files.write(file, java.util.Arrays.asList(propertyLines));
        PrefixPlugin plugin = new PrefixPlugin();
        plugin.setSourceName(sourceName);
        plugin.setPluginProperties(Map.of("mediaType", "properties", "location", "file:" + file));
        return plugin.getConfigSourceProperties();
    }


    @Test
    @DisplayName("every property name is prefixed with the source name")
    void propertiesArePrefixedWithTheSourceName() throws Exception {
        Map<String, String> properties = load("prefix", "test=w00t!", "other=value");
        assertEquals("w00t!", properties.get("prefix.test"));
        assertEquals("value", properties.get("prefix.other"));
    }

    @Test
    @DisplayName("the unprefixed name is not also present")
    void theOriginalNameIsReplaced() throws Exception {
        Map<String, String> properties = load("prefix", "test=w00t!");
        assertEquals(1, properties.size());
        assertTrue(properties.containsKey("prefix.test"));
    }

    @Test
    void adifferentSourceNameGivesADifferentPrefix() throws Exception {
        assertEquals("v", load("myApp", "key=v").get("myApp.key"));
    }

    @Test
    void anEmptyFileProducesNoProperties() throws Exception {
        assertTrue(load("prefix").isEmpty());
    }

    @Test
    void theRequiredPropertiesAreDeclared() {
        PrefixPlugin plugin = new PrefixPlugin();
        assertEquals(java.util.Set.of("mediaType", "location"), plugin.getRequiredPluginPropertyNames());
        assertEquals(java.util.Set.of(), plugin.getOptionalPluginPropertyNames());
    }

    @Test
    void aMissingLocationIsRejected() {
        PrefixPlugin plugin = new PrefixPlugin();
        plugin.setSourceName("prefix");
        assertThrows(
            Exception.class,
            () -> plugin.setPluginProperties(Map.of("mediaType", "properties"))
        );
    }

    @Test
    @DisplayName("only the `properties` media type is supported for now")
    void anUnsupportedMediaTypeIsRejected() throws Exception {
        Path file = tempDir.resolve("source.properties");
        Files.writeString(file, "a=1");
        PrefixPlugin plugin = new PrefixPlugin();
        plugin.setSourceName("prefix");
        assertThrows(
            Exception.class,
            () -> plugin.setPluginProperties(
                Map.of("mediaType", "somethingElse", "location", "file:" + file)
            )
        );
    }

    @Test
    void theVersionIsReported() {
        assertTrue(new PrefixPlugin().getPluginVersion() != null);
    }
}
