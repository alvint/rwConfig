package net.rabbitware.config.plugin.xml;

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
 * Tests for flattening an XML source into properties.
 *
 * <p>Most of these cover which repeated elements are collapsed into a single
 * comma-separated value and which fall back to indexed names. Repeated plain
 * values collapse whatever mix of types they hold, and only child elements keep
 * indexed names. XML text is
 * converted to numbers before it gets here, so a decimal arrives as a
 * {@code BigDecimal} and a large whole number as a {@code BigInteger}.
 */
class XmlPluginTest {

    @TempDir
    private Path tempDir;

    /** Flatten the given XML into properties. */
    private Map<String, String> load(String xml) throws Exception {
        Path file = tempDir.resolve("source.xml");
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xml);
        XmlPlugin plugin = new XmlPlugin();
        plugin.setSourceName("test");
        plugin.setPluginProperties(Map.of("location", "file:" + file));
        return plugin.getConfigSourceProperties();
    }

    /**
     * Flatten a document whose root holds repeated `v` elements with the given
     * values, which the parser treats as an array.
     */
    private Map<String, String> loadRepeated(String... values) throws Exception {
        StringBuilder xml = new StringBuilder("<root><x>");
        for (String value : values) {
            xml.append("<v>").append(value).append("</v>");
        }
        return load(xml.append("</x></root>").toString());
    }


    @Nested
    @DisplayName("repeated elements holding plain values become a comma-separated list")
    class Collapsed {

        @Test
        void strings() throws Exception {
            assertEquals("a,b,c", loadRepeated("a", "b", "c").get("root\\x\\v"));
        }

        @Test
        void integers() throws Exception {
            assertEquals("1,2,3", loadRepeated("1", "2", "3").get("root\\x\\v"));
        }

        @Test
        @DisplayName("decimals - the parser returns BigDecimal for these, not Double")
        void decimals() throws Exception {
            assertEquals("1.5,2.5,3.5", loadRepeated("1.5", "2.5", "3.5").get("root\\x\\v"));
        }

        @Test
        void negativeNumbers() throws Exception {
            assertEquals("-1,-2", loadRepeated("-1", "-2").get("root\\x\\v"));
            assertEquals("-1.5,-2.5", loadRepeated("-1.5", "-2.5").get("root\\x\\v"));
        }

        @Test
        @DisplayName("whole numbers too large for a long - the parser returns BigInteger")
        void veryLargeIntegers() throws Exception {
            assertEquals(
                "99999999999999999999,88888888888888888888",
                loadRepeated("99999999999999999999", "88888888888888888888").get("root\\x\\v")
            );
        }

        @Test
        void booleans() throws Exception {
            assertEquals("true,false", loadRepeated("true", "false").get("root\\x\\v"));
        }

        @Test
        @DisplayName("whole numbers and decimals together - `9.99, 10` is an ordinary price list")
        void integersMixedWithDecimals() throws Exception {
            assertEquals("1,2.5,3", loadRepeated("1", "2.5", "3").get("root\\x\\v"));
            assertEquals("9.99,10", loadRepeated("9.99", "10").get("root\\x\\v"));
        }

        @Test
        @DisplayName("an item's comma is escaped, so the item reads back whole")
        void itemsAreEscaped() throws Exception {
            // the escaping itself is ListValues' - see ListValuesTest in the config module.
            // No leading-space case here: the XML parser trims element text itself
            assertEquals("a\\,b,c", loadRepeated("a,b", "c").get("root\\x\\v"));
        }

        @Test
        @DisplayName("strings and numbers together - a `stringList` can read either")
        void stringsMixedWithNumbers() throws Exception {
            assertEquals("a,1", loadRepeated("a", "1").get("root\\x\\v"));
        }
    }


    @Nested
    @DisplayName("repeated elements holding child elements keep indexed names")
    class Indexed {

        @Test
        void childElements() throws Exception {
            Map<String, String> properties = load(
                "<root><x><v><a>1</a></v><v><a>2</a></v></x></root>"
            );
            assertNull(properties.get("root\\x\\v"));
            assertEquals("1", properties.get("root\\x\\v\\0\\a"));
            assertEquals("2", properties.get("root\\x\\v\\1\\a"));
        }

        @Test
        @DisplayName("one element with children among plain values is enough")
        void oneElementWithChildrenAmongPlainValues() throws Exception {
            Map<String, String> properties = load("<root><x><v>1</v><v><a>2</a></v></x></root>");
            assertNull(properties.get("root\\x\\v"));
            assertEquals("1", properties.get("root\\x\\v\\0"));
            assertEquals("2", properties.get("root\\x\\v\\1\\a"));
        }
    }


    @Test
    @DisplayName("the example from PLUGINS.md produces the properties it documents")
    void theDocumentedExample() throws Exception {
        Map<String, String> properties = load("""
            <foo>
              <ints>
                <val>1</val>
                <val>3</val>
                <val>5</val>
              </ints>
              <prices>
                <val>9.99</val>
                <val>10</val>
              </prices>
            </foo>
            """);
        assertEquals("1,3,5", properties.get("foo\\ints\\val"));
        assertEquals("9.99,10", properties.get("foo\\prices\\val"));
    }

    @Test
    @DisplayName("the nested-document example from PLUGINS.md, including repeated elements and xmlns")
    void theDocumentedNestedExample() throws Exception {
        Map<String, String> properties = load("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <groupId>com.foo</groupId>
                <artifactId>example</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-simple</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """);
        assertEquals("http://maven.apache.org/POM/4.0.0", properties.get("project\\xmlns"));
        assertEquals("com.foo", properties.get("project\\groupId"));
        assertEquals("example", properties.get("project\\artifactId"));
        assertEquals("org.slf4j", properties.get("project\\dependencies\\dependency\\0\\groupId"));
        assertEquals("slf4j-api", properties.get("project\\dependencies\\dependency\\0\\artifactId"));
        assertEquals("org.slf4j", properties.get("project\\dependencies\\dependency\\1\\groupId"));
        assertEquals("slf4j-simple", properties.get("project\\dependencies\\dependency\\1\\artifactId"));
    }

    @Test
    @DisplayName("an empty element is an empty value rather than a missing one")
    void emptyElements() throws Exception {
        assertEquals("", load("<root><a></a></root>").get("root\\a"));
    }

    @Test
    void attributesBecomeProperties() throws Exception {
        Map<String, String> properties = load("<root a=\"1\"><b>2</b></root>");
        assertEquals("1", properties.get("root\\a"));
        assertEquals("2", properties.get("root\\b"));
    }

    @Test
    void nestedElementsAreFlattenedWithBackslashes() throws Exception {
        assertEquals("deep", load("<a><b><c>deep</c></b></a>").get("a\\b\\c"));
    }

    @Nested
    @DisplayName("whether changes can be detected depends on the location")
    class ChangeDetection {

        private boolean supportedFor(String location) throws Exception {
            XmlPlugin plugin = new XmlPlugin();
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
            assertEquals(false, new XmlPlugin().isChangeDetectionSupported());
        }
    }
}
