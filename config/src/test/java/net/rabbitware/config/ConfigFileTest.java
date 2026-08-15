package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.rabbitware.config.Config.ConfigException;
import net.rabbitware.config.Config.PropertyNotFoundException;

/**
 * Tests for the syntax of the rwconfig file itself - line joining, escape
 * sequences, allowed values, ranges, and value types.
 *
 * <p>Note on escapes in this file: a config line like {@code string p = a\eb}
 * is written here as {@code "string p = a\\eb"}. A unicode escape has to be
 * written as {@code "\\u0041"} - the doubled backslash keeps javac from
 * expanding it before the string is compiled.
 */
class ConfigFileTest {

    /**
     * The config setup lines every test file needs. The system properties
     * source is the simplest source to declare, and unknown properties are
     * ignored so that the JVM's own system properties do not have to be
     * declared here.
     */
    private static final List<String> SETUP = List.of(
        "rwc.sources = system",
        "rwc.system.type = systemProperties",
        "rwc.system.ignoreUnknownProperties = true"
    );

    @TempDir
    private Path tempDir;

    /** Build a config file out of the given property lines and load it. */
    private Config config(String... propertyLines) throws IOException {
        List<String> lines = new ArrayList<>(SETUP);
        lines.addAll(Arrays.asList(propertyLines));
        Path file = tempDir.resolve("rwconfig");
        Files.write(file, lines);
        return ConfigFactory.create(new String[] {
            ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + file
        });
    }

    /** Assert that the given property lines are rejected, and return why. */
    private ConfigException rejected(String... propertyLines) {
        return assertThrows(ConfigException.class, () -> config(propertyLines));
    }

    /**
     * Load the given property lines and return the warnings that were logged
     * while doing so. slf4j-simple logs to {@code System.err} and looks it up
     * for each message, so it can be swapped out here.
     */
    private List<String> warningsFrom(String... propertyLines) throws IOException {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            config(propertyLines);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8)
            .lines()
            .filter(line -> line.contains("WARN"))
            .toList();
    }

    /** Assert that the given lines are rejected as a syntactically invalid line. */
    private void rejectedAsInvalidLine(String... propertyLines) {
        ConfigException e = rejected(propertyLines);
        assertTrue(
            e.getMessage().contains("invalid config line"),
            "expected an `invalid config line` error, but got: " + e.getMessage()
        );
    }


    //
    // line joining
    //

    @Nested
    @DisplayName("lines that end with a backslash are joined with the next line")
    class LineJoining {

        @Test
        void theBackslashIsRemovedAndTheLinesAreJoined() throws IOException {
            assertEquals("ab", config("string myProp = a\\", "b").gets("myProp"));
        }

        @Test
        void aValueCanBeSplitOverSeveralLines() throws IOException {
            Config config = config("stringList myList = a, b, \\", "c, \\", "d");
            assertEquals(List.of("a", "b", "c", "d"), config.getsl("myList"));
        }

        @Test
        void anAllowedValuesListCanBeSplitOverSeveralLines() throws IOException {
            Config config = config("int[0:50, \\", "100:150] myProp = 120");
            assertEquals(120, config.geti("myProp"));
        }

        @Test
        void aTrailingBackslashOnTheLastLineJustEndsTheLine() throws IOException {
            assertEquals("a", config("string myProp = a\\").gets("myProp"));
        }

        @Test
        void anEmptyLineEndsTheJoinedLine() throws IOException {
            Config config = config("string myProp = a\\", "", "string myOther = b");
            assertEquals("a", config.gets("myProp"));
            assertEquals("b", config.gets("myOther"));
        }

        @Test
        @DisplayName("a line is joined before comments are removed, so a comment swallows the next line")
        void aCommentThatEndsWithABackslashSwallowsTheNextLine() throws IOException {
            Config config = config("# a comment \\", "string myProp = surprise");
            assertThrows(PropertyNotFoundException.class, () -> config.gets("myProp"));
        }

        @Test
        @DisplayName("only a comment that starts a line is removed, so a joined `#` is part of the value")
        void aHashInTheMiddleOfAJoinedLineIsNotAComment() throws IOException {
            assertEquals("a# not a comment", config("string myProp = a\\", "# not a comment").gets("myProp"));
        }

        @Test
        @DisplayName("a line ending in two backslashes joins as well - the test is a plain suffix test")
        void twoTrailingBackslashesAlsoJoin() {
            // the last backslash joins the lines and is removed, which leaves
            // `a\b` - and `\b` is not a valid escape sequence
            ConfigException e = rejected("string myProp = a\\\\", "b");
            assertTrue(
                e.getMessage().contains("invalid escape sequence `\\b`"),
                "expected the joined value to be `a\\b`, but got: " + e.getMessage()
            );
        }
    }


    //
    // escape sequences in values
    //

    @Nested
    @DisplayName("escape sequences in a value")
    class ValueEscapes {

        @Test
        void backslashEscapesItself() throws IOException {
            assertEquals("a\\b", config("string myProp = a\\\\b").gets("myProp"));
        }

        @Test
        void eIsAnEmptyString() throws IOException {
            assertEquals("ab", config("string myProp = a\\eb").gets("myProp"));
        }

        @Test
        void whitespaceEscapes() throws IOException {
            Config config = config(
                "string myTab = a\\tb",
                "string myNewline = a\\nb",
                "string myReturn = a\\rb"
            );
            assertEquals("a\tb", config.gets("myTab"));
            assertEquals("a\nb", config.gets("myNewline"));
            assertEquals("a\rb", config.gets("myReturn"));
        }

        @Test
        void unicodeEscapes() throws IOException {
            assertEquals("aAb", config("string myProp = a\\u0041b").gets("myProp"));
        }

        @Test
        @DisplayName("an escaped leading space is kept, and trailing spaces are always kept")
        void anEscapedLeadingSpaceIsPreserved() throws IOException {
            // only the escaped space is needed - any space after it is already
            // part of the value
            assertEquals(" spaced  ", config("string myProp = \\ spaced  ").gets("myProp"));
            assertEquals("   spaced", config("string myProp = \\   spaced").gets("myProp"));
        }

        @Test
        @DisplayName("an escaped space after the start of a value is kept, but warns - the space needs no escaping there")
        void anEscapedSpaceLaterInAValueIsJustASpace() throws IOException {
            // after another character
            assertEquals("a b", config("string myProp = a\\ b").gets("myProp"));
            // after an escape that resolves to nothing
            assertEquals(" b", config("string myProp = \\e\\ b").gets("myProp"));
            // a second escaped space
            assertEquals(" a b", config("string myProp = \\ a\\ b").gets("myProp"));
            // after an escaped backslash
            assertEquals("a\\ b", config("string myProp = a\\\\\\ b").gets("myProp"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "string myProp = a\\ b",
            "string myProp = \\e\\ b",
            "string myProp = \\ a\\ b",
        })
        @DisplayName("a misplaced escaped space is warned about rather than rejected")
        void aMisplacedEscapedSpaceIsWarnedAbout(String line) throws IOException {
            assertTrue(
                warningsFrom(line).stream().anyMatch(w -> w.contains("escaped space is only meaningful at the start")),
                "expected a warning about the escaped space, but got: " + warningsFrom(line)
            );
        }

        @Test
        void anEscapedSpaceAtTheStartDoesNotWarn() throws IOException {
            assertEquals(List.of(), warningsFrom("string myProp = \\ leading"));
        }

        @Test
        @DisplayName("an escaped backslash followed by a space is not an escaped space")
        void anEscapedBackslashBeforeASpaceIsFine() throws IOException {
            assertEquals("a\\ b", config("string myProp = a\\\\ b").gets("myProp"));
        }

        @Test
        void anEscapedSpaceIsStillAllowedAfterLeadingWhitespace() throws IOException {
            // each item of a non-string list keeps the whitespace that follows
            // its comma, so the escape is still at the start of the item
            assertEquals(List.of(" a", " b"), config("stringList myList = \\ a, \\ b").getsl("myList"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "string myProp = a\\qb",     // not an escape sequence
            "string myProp = a\\[b",     // `[` is used as-is, so it is never escaped
            "string myProp = a\\u12b",   // a unicode escape needs four hex digits
        })
        void invalidEscapeSequencesAreRejected(String line) {
            ConfigException e = rejected(line);
            assertTrue(
                e.getMessage().contains("invalid escape sequence"),
                "expected an `invalid escape sequence` error, but got: " + e.getMessage()
            );
        }

        @Test
        void aValueCannotEndWithALoneBackslash() {
            // the joiner takes the trailing backslash off the last line, which
            // leaves a value that ends with the backslash that preceded it
            ConfigException e = rejected("string myProp = a\\\\");
            assertTrue(
                e.getMessage().contains("invalid ending backslash"),
                "expected an `invalid ending backslash` error, but got: " + e.getMessage()
            );
        }
    }


    //
    // escape sequences in list items
    //

    @Nested
    @DisplayName("escape sequences in the items of a list")
    class ListItemEscapes {

        @Test
        void anEscapedCommaIsPartOfTheItem() throws IOException {
            Config config = config("stringList myList = to be\\, or not to be, seize the day");
            assertEquals(List.of("to be, or not to be", "seize the day"), config.getsl("myList"));
        }

        @Test
        void eIsAnEmptyItem() throws IOException {
            assertEquals(List.of("a", "", "b"), config("stringList myList = a, \\e, b").getsl("myList"));
        }

        @Test
        void eachItemCanEscapeItsOwnLeadingSpace() throws IOException {
            assertEquals(List.of(" a", " b"), config("stringList myList = \\ a, \\ b").getsl("myList"));
        }

        @Test
        @DisplayName("each item is its own value, so an escaped space later in an item warns")
        void anEscapedSpaceLaterInAnItemWarns() throws IOException {
            assertEquals(List.of("a", "b c"), config("stringList myList = a, b\\ c").getsl("myList"));
            assertTrue(
                warningsFrom("stringList myList = a, b\\ c").stream()
                    .anyMatch(w -> w.contains("escaped space is only meaningful at the start")),
                "expected a warning about the escaped space in the second item"
            );
        }

        @Test
        void unicodeEscapesWorkInItems() throws IOException {
            assertEquals(List.of("aAb", "c"), config("stringList myList = a\\u0041b, c").getsl("myList"));
        }
    }


    //
    // allowed values
    //

    @Nested
    @DisplayName("allowed values")
    class AllowedValues {

        @Test
        void aValueOutsideTheAllowedValuesIsRejected() {
            ConfigException e = rejected("string[home, work] myProp = other");
            assertTrue(
                e.getMessage().contains("is not allowed"),
                "expected a `not allowed` error, but got: " + e.getMessage()
            );
        }

        @Test
        void anEmptyAllowedValuesListIsRejected() {
            rejectedAsInvalidLine("string[] myProp = a");
        }

        @Test
        @DisplayName("a `[` needs no escaping once the list is open")
        void anOpeningBracketIsUsedAsIs() throws IOException {
            assertEquals("g[h", config("string[g[h, c] myProp = g[h").gets("myProp"));
        }

        @Test
        void anOpeningBracketIsAlsoUsedAsIsInAValue() throws IOException {
            assertEquals("a[b", config("string myProp = a[b").gets("myProp"));
        }

        @Test
        @DisplayName("`]`, `,` and `:` are meaningful in the list, so they have to be escaped")
        void theCharactersThatDelimitTheListCanBeEscaped() throws IOException {
            Config config = config("string[a\\,b, c\\:d, e\\]f, g[h\\]] myProp = e]f");
            assertEquals("e]f", config.gets("myProp"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "string[a\\\\b, c] myProp = c",   // \\ - an escaped backslash
            "string[a\\eb, c] myProp = c",    // \e - an empty string
            "string[a\\nb, c] myProp = c",    // \n
            "string[a\\rb, c] myProp = c",    // \r
            "string[a\\tb, c] myProp = c",    // \t
            "string[a\\u0041b, c] myProp = c" // a unicode escape
        })
        @DisplayName("an allowed value accepts the same escape sequences a value does")
        void valueEscapeSequencesAreAllowedInAnAllowedValue(String line) throws IOException {
            assertEquals("c", config(line).gets("myProp"));
        }

        @Test
        void theEscapeSequencesResolveInAnAllowedValueToo() throws IOException {
            // the declared value has to match the allowed value after both are
            // unescaped, so this only passes if the list resolved `A` too
            assertEquals("aAb", config("string[a\\u0041b, c] myProp = a\\u0041b").gets("myProp"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "string[a\\qb, c] myProp = c",    // not an escape sequence
            "string[a\\[b, c] myProp = c",    // `[` is used as-is, so it is never escaped
        })
        void anUnknownEscapeSequenceIsNotAValidAllowedValue(String line) {
            rejectedAsInvalidLine(line);
        }

        @Test
        @DisplayName("a `\\u` is a valid allowed value, so a bad unicode escape is caught when it is unescaped")
        void aMalformedUnicodeEscapeIsRejectedInAnAllowedValue() {
            ConfigException e = rejected("string[a\\u12b, c] myProp = c");
            assertTrue(
                e.getMessage().contains("invalid escape sequence"),
                "expected an `invalid escape sequence` error, but got: " + e.getMessage()
            );
        }

        @Test
        @DisplayName("only the first character of an allowed value may be an escaped space")
        void anAllowedValueCanEscapeItsLeadingSpace() throws IOException {
            assertEquals(" a", config("string[\\ a, b] myProp = \\ a").gets("myProp"));
        }

        @Test
        void anEscapedSpaceLaterInAnAllowedValueIsRejected() {
            rejectedAsInvalidLine("string[a\\ b, c] myProp = c");
        }
    }


    //
    // ranges
    //

    @Nested
    @DisplayName("an allowed value can be a `<min>:<max>` range")
    class Ranges {

        @Test
        void aNumericRangeIsInclusive() throws IOException {
            assertEquals(0, config("int[0:100] myProp = 0").geti("myProp"));
            assertEquals(100, config("int[0:100] myProp = 100").geti("myProp"));
        }

        @Test
        void aValueOutsideANumericRangeIsRejected() {
            rejected("int[0:100] myProp = 101");
        }

        @Test
        void severalRangesAndSingleValuesCanBeMixed() throws IOException {
            Config config = config("intList[80, 1024:65535] myPorts = 1520, 8080, 80");
            assertEquals(List.of(1520, 8080, 80), config.getil("myPorts"));
        }

        @Test
        void aStringRangeComparesLexicographically() throws IOException {
            assertEquals("5", config("string[0:9, A:F] myProp = 5").gets("myProp"));
            rejected("string[0:9, A:F] myProp = G");
        }

        @Test
        @DisplayName("`\\e` is an empty string, so `\\e:z` is a range with an open lower bound")
        void anEmptyStringCanBeUsedAsAnOpenLowerBound() throws IOException {
            assertEquals("hello", config("string[\\e:z] myProp = hello").gets("myProp"));
            assertEquals("A", config("string[\\e:z] myProp = A").gets("myProp"));
            assertEquals("", config("string[\\e:z] myProp =").gets("myProp"));
            // the upper bound still applies
            rejected("string[\\e:z] myProp = zzz");
        }

        @Test
        @DisplayName("an escaped colon is part of the value rather than a range separator")
        void anEscapedColonDoesNotMakeARange() throws IOException {
            assertEquals("a:b", config("string[a\\:b, c] myProp = a\\:b").gets("myProp"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "int[0:10:20] myProp = 5",   // too many colons
            "long[0:100:] myProp = 90",  // trailing colon
            "string[a:b:c] myProp = a",  // too many colons, string type
        })
        void aRangeWithTooManyColonsIsRejected(String line) {
            rejected(line);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "int[:100] myProp = 5",      // no lower bound
            "int[100:] myProp = 150",    // no upper bound
            "string[:z] myProp = a",     // an allowed value is never empty, even for strings
        })
        void aRangeWithAMissingBoundIsRejected(String line) {
            rejectedAsInvalidLine(line);
        }
    }


    //
    // lists
    //

    @Nested
    @DisplayName("list values")
    class Lists {

        @Test
        void anEmptyValueIsAnEmptyList() throws IOException {
            assertEquals(List.of(), config("intList myList =").getil("myList"));
            assertEquals(List.of(), config("stringList myList =").getsl("myList"));
        }

        @Test
        @DisplayName("a string list is the only list that can have blank items")
        void aStringListCanHaveBlankItems() throws IOException {
            assertEquals(List.of("a", "", "b"), config("stringList myList = a,,b").getsl("myList"));
            assertEquals(List.of("", "a"), config("stringList myList = , a").getsl("myList"));
            assertEquals(List.of("a", ""), config("stringList myList = a,").getsl("myList"));
        }

        @Test
        @DisplayName("a single `\\e` is a list with one blank item, not an empty list")
        void aStringListCanHoldOneBlankItem() throws IOException {
            assertEquals(List.of(""), config("stringList myList = \\e").getsl("myList"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "intList myList = 1,,2",
            "intList myList = , 1",
            "intList myList = 1,",
            "doubleList myList = 1.0, , 2.0",
            "booleanList myList = true,",
        })
        @DisplayName("a blank item in any other list is rejected")
        void aBlankItemInANonStringListIsRejected(String line) {
            rejected(line);
        }

        @Test
        void aBlankAllowedValueIsRejectedForANonStringType() {
            rejectedAsInvalidLine("intList[1,,10] myList = 1");
        }

        @Test
        void everyItemMustBeAnAllowedValue() {
            rejected("intList[1:10] myList = 1, 2, 77");
        }
    }


    //
    // value types
    //

    @Nested
    @DisplayName("value types")
    class Types {

        @Test
        void theDefaultTypeIsString() throws IOException {
            Config config = config("myProp = 42");
            assertEquals(Config.PropertyType.STRING, config.getType("myProp"));
            assertEquals("42", config.gets("myProp"));
        }

        @Test
        void aTypeCanBeOmittedWhenThereAreAllowedValues() throws IOException {
            assertEquals("one", config("[one, two, three] myProp = one").gets("myProp"));
        }

        @Test
        void numericTypes() throws IOException {
            Config config = config(
                "int myInt = 42",
                "long myLong = 5000000000",
                "double myDouble = .314159e1"
            );
            assertEquals(42, config.geti("myInt"));
            assertEquals(5000000000L, config.getl("myLong"));
            assertEquals(3.14159, config.getd("myDouble"), 1e-9);
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "yes", "on", "1", "TRUE", "Yes"})
        void truthyBooleans(String value) throws IOException {
            assertTrue(config("boolean myProp = " + value).getb("myProp"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "no", "off", "0", "FALSE", "No"})
        void falsyBooleans(String value) throws IOException {
            assertEquals(false, config("boolean myProp = " + value).getb("myProp"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "int myProp = 50.5",
            "int myProp = notanumber",
            "boolean myProp = maybe",
            "double myProp = 1.2.3",
        })
        void aValueThatDoesNotMatchItsTypeIsRejected(String line) {
            rejected(line);
        }

        @Test
        void anUndeclaredPropertyIsNotFound() throws IOException {
            Config config = config("string myProp = a");
            assertThrows(PropertyNotFoundException.class, () -> config.gets("somethingElse"));
        }

        @Test
        void askingForTheWrongTypeIsRejected() throws IOException {
            Config config = config("int myProp = 42");
            assertThrows(ConfigException.class, () -> config.gets("myProp"));
        }
    }


    //
    // property names
    //

    @Nested
    @DisplayName("property names")
    class PropertyNames {

        @ParameterizedTest
        @ValueSource(strings = {
            "firstName = a",
            "first-name = a",
            "my.dotted.name = a",
            "my_underscored_name = a",
            "backslashes\\are\\allowed\\in\\names = a",
        })
        void validNames(String line) throws IOException {
            config(line);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "first name = a",   // a space is not allowed
            ".rc = a",          // cannot start with a period
            "1stName = a",      // cannot start with a number
        })
        void invalidNames(String line) {
            rejectedAsInvalidLine(line);
        }

        @Test
        void aDuplicatePropertyIsRejected() {
            ConfigException e = rejected("string myProp = a", "string myProp = b");
            assertTrue(
                e.getMessage().contains("duplicate config line"),
                "expected a `duplicate config line` error, but got: " + e.getMessage()
            );
        }
    }


    //
    // a type is only a type when something separates it from the name
    //

    @Nested
    @DisplayName("a name that starts with the name of a type is still just a name")
    class NamesThatLookLikeTypes {

        @ParameterizedTest
        @ValueSource(strings = {
            "boolean", "int", "long", "double", "string",
            "booleanList", "intList", "longList", "doubleList", "stringList",
        })
        @DisplayName("<type>Suffix is a string property named <type>Suffix, not a typed property named Suffix")
        void aNameBeginningWithATypeName(String type) throws IOException {
            String name = type + "Suffix";
            Config config = config(name + " = a value");
            assertEquals(Set.of(name), Set.copyOf(config.getPropertyNames()));
            assertEquals(Config.PropertyType.STRING, config.getType(name));
            assertEquals("a value", config.gets(name));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "longitude", "intervalSeconds", "stringify", "booleanFlag", "doubleClickMs", "intranet",
        })
        @DisplayName("names people actually write, which used to lose their leading characters")
        void realisticNames(String name) throws IOException {
            Config config = config(name + " = 1");
            assertEquals(Set.of(name), Set.copyOf(config.getPropertyNames()));
            assertEquals("1", config.gets(name));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "boolean myProp = true", "int myProp = 1", "long myProp = 1", "double myProp = 1.5",
            "string myProp = a", "booleanList myProp = true", "intList myProp = 1",
            "longList myProp = 1", "doubleList myProp = 1.5", "stringList myProp = a",
        })
        @DisplayName("a type separated from the name by a space is still a type")
        void aTypeFollowedByASpace(String line) throws IOException {
            assertEquals(Set.of("myProp"), Set.copyOf(config(line).getPropertyNames()));
        }

        @Test
        @DisplayName("a type followed directly by an allowed values list is still a type")
        void aTypeFollowedByABracket() throws IOException {
            Config config = config("int[0:100] myProp = 90");
            assertEquals(Config.PropertyType.INT, config.getType("myProp"));
            assertEquals(90, config.geti("myProp"));
        }

        @Test
        @DisplayName("a type that starts with the name of another type resolves to the longer one")
        void aTypeThatStartsWithAnotherType() throws IOException {
            Config config = config("intList myPorts = 1, 2", "longList myLongs = 3");
            assertEquals(Config.PropertyType.INT_LIST, config.getType("myPorts"));
            assertEquals(Config.PropertyType.LONG_LIST, config.getType("myLongs"));
            assertEquals(List.of(1, 2), config.getil("myPorts"));
        }

        @Test
        @DisplayName("the list examples in PLUGINS.md can be declared - they could not before")
        void theNamesUsedInTheDocumentation() throws IOException {
            Config config = config("stringList strings = a, b, c", "intList ints = 1, 2, 3");
            assertEquals(List.of("a", "b", "c"), config.getsl("strings"));
            assertEquals(List.of(1, 2, 3), config.getil("ints"));
        }

        @Test
        @DisplayName("a property may be named exactly after a type")
        void aNameThatIsExactlyATypeName() throws IOException {
            Config config = config("string = a value");
            assertEquals(Set.of("string"), Set.copyOf(config.getPropertyNames()));
            assertEquals("a value", config.gets("string"));
        }
    }


    //
    // comments and whitespace
    //

    @Nested
    @DisplayName("comments and whitespace")
    class CommentsAndWhitespace {

        @Test
        @DisplayName("a comment line may start with a # or a !")
        void bothCommentCharacters() throws IOException {
            Config config = config("# a hash comment", "! a bang comment", "myProp = a");
            assertEquals(Set.of("myProp"), Set.copyOf(config.getPropertyNames()));
        }

        @Test
        void aCommentMayBeIndented() throws IOException {
            assertEquals(Set.of("myProp"), Set.copyOf(config("    # indented", "myProp = a").getPropertyNames()));
        }

        @Test
        @DisplayName("a # that is not at the start of a line is part of the value")
        void aHashInsideAValue() throws IOException {
            assertEquals("a # b", config("myProp = a # b").gets("myProp"));
        }

        @Test
        void blankLinesAreIgnored() throws IOException {
            Config config = config("", "   ", "myProp = a", "", "myOther = b");
            assertEquals(Set.of("myProp", "myOther"), Set.copyOf(config.getPropertyNames()));
        }

        @Test
        @DisplayName("a declaration may be indented, and space around the `=` is ignored")
        void surroundingWhitespaceIsIgnored() throws IOException {
            assertEquals("a", config("    int myInt = 1", "   string myProp   =   a").gets("myProp"));
        }

        @Test
        @DisplayName("trailing whitespace in a value is kept, leading whitespace is not")
        void whitespaceAroundAValue() throws IOException {
            assertEquals("a  ", config("string myProp =    a  ").gets("myProp"));
        }
    }
}
