package net.rabbitware.config.plugin.dotenv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for parsing a `.env` file.
 *
 * <p>The format has no specification, so these pin the dialect the plugin
 * implements rather than anything anyone has standardised. Where
 * implementations disagree - inline comments especially - the rule followed is
 * Docker Compose's, on the grounds that a `.env` file in a Java project has
 * most likely been through Compose.
 */
class DotEnvPluginTest {

    private Map<String, String> parse(String contents) throws Exception {
        return DotEnvPlugin.parse(contents);
    }

    @Nested
    @DisplayName("the basics")
    class Basics {

        @Test
        @DisplayName("a plain assignment")
        void plainAssignment() throws Exception {
            assertEquals(Map.of("PORT", "8080"), parse("PORT=8080\n"));
        }

        @Test
        @DisplayName("blank lines and whole-line comments are skipped")
        void commentsAndBlanks() throws Exception {
            assertEquals(Map.of("A", "1"), parse("# a comment\n\n   \n   # indented comment\nA=1\n"));
        }

        @Test
        @DisplayName("space around the name, the `=`, and the value is ignored")
        void whitespaceAroundTheAssignment() throws Exception {
            assertEquals(Map.of("A", "1"), parse("  A  =  1  \n"));
        }

        @Test
        @DisplayName("an `export` prefix is allowed, so a file meant to be sourced also works")
        void exportPrefix() throws Exception {
            assertEquals(Map.of("A", "1"), parse("export A=1\n"));
        }

        @Test
        @DisplayName("a value may contain an `=`")
        void valueContainingEquals() throws Exception {
            assertEquals(Map.of("TOKEN", "abc=def=="), parse("TOKEN=abc=def==\n"));
        }

        @Test
        @DisplayName("an empty value is empty, not missing")
        void emptyValue() throws Exception {
            assertEquals(Map.of("EMPTY", ""), parse("EMPTY=\n"));
        }

        @Test
        @DisplayName("declaration order is kept")
        void orderIsKept() throws Exception {
            assertEquals("[C, B, A]", parse("C=3\nB=2\nA=1\n").keySet().toString());
        }
    }

    @Nested
    @DisplayName("comments after a value")
    class InlineComments {

        @Test
        @DisplayName("are stripped when preceded by a space")
        void strippedWhenSpaced() throws Exception {
            assertEquals(Map.of("A", "1"), parse("A=1 # this is a comment\n"));
        }

        @Test
        @DisplayName("are part of the value when not - a `#` in a password is not a comment")
        void notStrippedWhenTouching() throws Exception {
            assertEquals(Map.of("PASSWORD", "pa#ssword"), parse("PASSWORD=pa#ssword\n"));
            assertEquals(Map.of("COLOUR", "#ff0000"), parse("COLOUR=#ff0000\n"));
        }

        @Test
        @DisplayName("follow the closing quote for a quoted value")
        void afterAQuotedValue() throws Exception {
            assertEquals(Map.of("A", "1"), parse("A=\"1\" # comment\n"));
        }

        @Test
        @DisplayName("are literal inside quotes")
        void insideQuotes() throws Exception {
            assertEquals(Map.of("A", "1 # not a comment"), parse("A='1 # not a comment'\n"));
        }
    }

    @Nested
    @DisplayName("quoting")
    class Quoting {

        @Test
        @DisplayName("single quotes are literal - no escapes, no expansion")
        void singleQuotesAreLiteral() throws Exception {
            assertEquals(Map.of("A", "a\\nb"), parse("A='a\\nb'\n"));
        }

        @Test
        @DisplayName("double quotes process the usual escapes")
        void doubleQuoteEscapes() throws Exception {
            assertEquals(Map.of("A", "a\nb\tc"), parse("A=\"a\\nb\\tc\"\n"));
            assertEquals(Map.of("A", "say \"hi\""), parse("A=\"say \\\"hi\\\"\"\n"));
            assertEquals(Map.of("A", "back\\slash"), parse("A=\"back\\\\slash\"\n"));
        }

        @Test
        @DisplayName("an unknown escape keeps its backslash, so a Windows path survives")
        void unknownEscapesAreLeftAlone() throws Exception {
            assertEquals(Map.of("PATH", "C:\\Users\\me"), parse("PATH=\"C:\\Users\\me\"\n"));
        }

        @Test
        @DisplayName("quotes keep leading and trailing spaces the value is meant to have")
        void quotesPreserveSpaces() throws Exception {
            assertEquals(Map.of("A", "  padded  "), parse("A=\"  padded  \"\n"));
        }

        @Test
        @DisplayName("a quoted value may span lines - a certificate, say")
        void multiLineValues() throws Exception {
            Map<String, String> parsed = parse("KEY=\"line one\nline two\"\nNEXT=after\n");
            assertEquals("line one\nline two", parsed.get("KEY"));
            assertEquals("after", parsed.get("NEXT"), "parsing resumes after the closing quote");
        }
    }

    @Nested
    @DisplayName("things that are not a valid `.env` file")
    class Rejected {

        @Test
        @DisplayName("a line with no `=`")
        void noAssignment() {
            Exception e = assertThrows(Exception.class, () -> parse("PORT 8080\n"));
            assertTrue(e.getMessage().contains("line 1"), e.getMessage());
        }

        @Test
        @DisplayName("a quote that is never closed, rather than a silently truncated value")
        void unclosedQuote() {
            Exception e = assertThrows(Exception.class, () -> parse("A=\"never ends\nB=2\n"));
            assertTrue(e.getMessage().contains("never closed"), e.getMessage());
        }

        @Test
        @DisplayName("text after the closing quote")
        void trailingRubbish() {
            Exception e = assertThrows(Exception.class, () -> parse("A=\"one\" two\n"));
            assertTrue(e.getMessage().contains("after the closing"), e.getMessage());
        }

        @Test
        @DisplayName("a name with a space in it")
        void spaceInName() {
            Exception e = assertThrows(Exception.class, () -> parse("MY KEY=1\n"));
            assertTrue(e.getMessage().contains("whitespace in the name"), e.getMessage());
        }

        @Test
        @DisplayName("nothing before the `=`")
        void noName() {
            Exception e = assertThrows(Exception.class, () -> parse("=1\n"));
            assertTrue(e.getMessage().contains("no name"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("what is deliberately not supported")
    class NotSupported {

        @Test
        @DisplayName("variables are not expanded - the text is the value")
        void noInterpolation() throws Exception {
            assertEquals(Map.of("A", "${OTHER}"), parse("A=${OTHER}\n"));
            assertEquals(Map.of("A", "$OTHER"), parse("A=\"$OTHER\"\n"));
        }
    }
}
