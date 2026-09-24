package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.rabbitware.config.plugin.api.ListValues;

/**
 * Tests for {@link ListValues}, the helper plugins use to write an array as one
 * list value.
 *
 * <p>These live here rather than beside the helper because the promise it makes
 * is about the parser: whatever items go in, a list property reads back exactly
 * those items. Only a real {@code ConfigFactory} can check that. The value is
 * passed as a system property, which reaches the parser untouched. A
 * {@code .properties} file would apply its own escapes first, and a command
 * line argument cannot hold a newline.
 */
class ListValuesTest {

    @TempDir
    private Path tempDir;

    /** Join the items, then read them back through a list property of the given type. */
    private Config roundTrip(String type, List<String> items) throws IOException {
        Path file = tempDir.resolve("rwconfig");
        Files.write(file, List.of(
            "rwc.sources = sys",
            "rwc.sys.type = systemProperties",
            type + " listValuesTestProperty"
        ));
        System.setProperty("listValuesTestProperty", ListValues.join(items, Function.identity()));
        try {
            return ConfigFactory.create(new String[] {
                ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file
            });
        } finally {
            System.clearProperty("listValuesTestProperty");
        }
    }

    private void assertRoundTrips(List<String> items) throws IOException {
        assertEquals(items, roundTrip("stringList", items).getStringList("listValuesTestProperty"));
    }


    @Nested
    @DisplayName("every item reads back exactly as it went in")
    class RoundTrip {

        @Test
        void plainItems() throws IOException {
            assertRoundTrips(List.of("a", "b", "c"));
        }

        @Test
        @DisplayName("a comma inside an item does not split it")
        void commas() throws IOException {
            assertRoundTrips(List.of("a,b", "c"));
            assertRoundTrips(List.of("a,", ",b", ","));
        }

        @Test
        @DisplayName("leading whitespace is kept, though the list splitter trims it after a comma")
        void leadingWhitespace() throws IOException {
            assertRoundTrips(List.of(" a", " b"));
            assertRoundTrips(List.of("a", "\tb", "\nc", "  d"));
        }

        @Test
        @DisplayName("an item ending in an escaped backslash does not swallow the next one")
        void itemEndingInABackslash() throws IOException {
            // an item is in value syntax, so the text `a\\` is the value `a\`
            assertEquals(
                List.of("a\\", "b"),
                roundTrip("stringList", List.of("a\\\\", "b")).getStringList("listValuesTestProperty"));
        }

        @Test
        @DisplayName("trailing whitespace is kept, which it always was")
        void trailingWhitespace() throws IOException {
            assertRoundTrips(List.of("a ", "b\t"));
        }

        @Test
        @DisplayName("a single empty item is a list of one empty string, not an empty list")
        void aSingleEmptyItem() throws IOException {
            assertRoundTrips(List.of(""));
        }

        @Test
        @DisplayName("empty items alongside others are kept too")
        void emptyItems() throws IOException {
            assertRoundTrips(List.of("a", "", "b"));
            assertRoundTrips(List.of("", ""));
        }

        @Test
        @DisplayName("no items at all is an empty list")
        void noItems() throws IOException {
            assertRoundTrips(List.of());
        }

        @Test
        @DisplayName("the declared type decides how the items are read")
        void otherListTypes() throws IOException {
            assertEquals(List.of(9.99, 10.0), roundTrip("doubleList", List.of("9.99", "10")).getDoubleList("listValuesTestProperty"));
            assertEquals(
                List.of(new java.math.BigDecimal("9.99"), new java.math.BigDecimal("10")),
                roundTrip("bigDecimalList", List.of("9.99", "10")).getBigDecimalList("listValuesTestProperty"));
        }
    }


    @Nested
    @DisplayName("what the joined text looks like")
    class Text {

        @Test
        @DisplayName("plain items are simply comma-separated")
        void plain() {
            assertEquals("a,b,c", ListValues.join(List.of("a", "b", "c"), Function.identity()));
        }

        @Test
        @DisplayName("a comma is escaped, and leading whitespace is guarded by `\\e`, the empty string")
        void escapes() {
            assertEquals("a\\,b,\\e c", ListValues.join(List.of("a,b", " c"), Function.identity()));
        }

        @Test
        @DisplayName("a single empty item is `\\e`, and no items is nothing")
        void empty() {
            assertEquals("\\e", ListValues.join(List.of(""), Function.identity()));
            assertEquals(",", ListValues.join(List.of("", ""), Function.identity()));
            assertEquals("", ListValues.join(List.of(), Function.identity()));
        }

        @Test
        @DisplayName("each item's text comes from the function the plugin passes")
        void textFunction() {
            assertEquals("1,null,true", ListValues.join(java.util.Arrays.asList(1, null, true), String::valueOf));
        }
    }


    @Test
    @DisplayName("`allAreValues` is true only when every item passes the plugin's test")
    void allAreValues() {
        assertTrue(ListValues.allAreValues(List.of("a", "b"), item -> true));
        assertFalse(ListValues.allAreValues(List.of("a", "{object}"), item -> !item.startsWith("{")));
        assertTrue(ListValues.allAreValues(List.of(), item -> false), "an empty array is a list value");
    }
}
